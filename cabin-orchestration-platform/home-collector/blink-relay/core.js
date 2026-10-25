// Pure logic for the Blink notification relay (no MQTT, no Termux calls), so it can be unit-tested.
//
// Why this exists: Blink's own motion API is unusable on this account, so the Blink app's phone
// notification is the only motion signal. This runs on an always-on phone (the Home Termux
// phone), reads the Android notification tray via Termux:API, and publishes the camera name to
// cabin/blink/motion, the same topic the HA Companion automation uses (publishing twice is
// harmless: liveview start is idempotent). It also publishes a retained heartbeat that
// cabin-backend's BlinkRelayPulse turns into an alert when it goes quiet.

'use strict';

const BLINK_PACKAGE = 'com.immediasemi.android.blink';
const MOTION_TOPIC = 'cabin/blink/motion';
const HEARTBEAT_TOPIC = 'cabin/blink/relay/heartbeat';

// Same mapping as cabin_security.yaml's cabin_security_publish_blink_motion automation.
function cameraFor(text) {
  const t = String(text || '');
  if (/Outdoor 4|DHEE/i.test(t)) return 'driveway';
  if (/AldrichFront/i.test(t)) return 'home_aldrich_front';
  return null;
}

function notificationText(n) {
  // termux-notification-list gives title/content; the literal string "null" means unset.
  return [n.title, n.content, n.text, n.subText, n.bigText]
    .filter((s) => s && s !== 'null')
    .join(' ');
}

function seenKey(n) {
  return [n.key || `${n.id}|${n.tag || ''}`, n.when || '', notificationText(n)].join('|');
}

function parseWhen(when, now) {
  const ms = Date.parse(String(when || '').replace(' ', 'T'));
  return Number.isFinite(ms) ? ms : now;
}

class Relay {
  /**
   * @param {object} o
   * @param {() => Promise<object[]>} o.listNotifications  rejects if the tray cannot be read
   * @param {(topic: string, payload: string, opts?: object) => Promise<void>} o.publish
   * @param {() => number} [o.now]
   * @param {number} [o.maxAgeMs]  ignore notifications older than this (stale tray entries)
   */
  constructor({ listNotifications, publish, now = Date.now, maxAgeMs = 120000, seenMax = 500, device = 'termux-relay', log = () => {} }) {
    Object.assign(this, { listNotifications, publish, now, maxAgeMs, seenMax, device, log });
    this.seen = new Set();
    this.primed = false;
    this.lastOkAt = null;
    this.startedAt = now();
  }

  remember(key) {
    this.seen.add(key);
    if (this.seen.size > this.seenMax) this.seen.delete(this.seen.values().next().value);
  }

  /** One pass over the tray. Throws if the tray cannot be read (so no heartbeat is sent). */
  async tick() {
    const list = await this.listNotifications();
    const now = this.now();
    this.lastOkAt = now;
    const blink = (Array.isArray(list) ? list : []).filter((n) => n && n.packageName === BLINK_PACKAGE);
    if (!this.primed) {
      // Whatever is already in the tray predates this process: never replay it.
      blink.forEach((n) => this.remember(seenKey(n)));
      this.primed = true;
      return [];
    }
    const sent = [];
    for (const n of blink) {
      const key = seenKey(n);
      if (this.seen.has(key)) continue;
      if (now - parseWhen(n.when, now) > this.maxAgeMs) { this.remember(key); continue; }
      const camera = cameraFor(notificationText(n));
      if (!camera) { this.remember(key); continue; }
      try {
        await this.publish(MOTION_TOPIC, camera, { qos: 1, retain: false });  // never retained
      } catch (e) {
        this.log(`publish failed, will retry: ${e.message}`);
        continue;  // not remembered: retried next tick while still younger than maxAgeMs
      }
      this.remember(key);
      sent.push(camera);
      this.log(`motion -> ${camera}`);
    }
    return sent;
  }

  /** Retained heartbeat; only meaningful if the last tick could read the tray. */
  async heartbeat() {
    const now = this.now();
    if (this.lastOkAt === null || now - this.lastOkAt > 60000) return false;
    await this.publish(HEARTBEAT_TOPIC, JSON.stringify({
      device: this.device,
      ts: new Date(now).toISOString(),
      uptime_s: Math.round((now - this.startedAt) / 1000),
    }), { qos: 1, retain: true });
    return true;
  }
}

module.exports = { Relay, cameraFor, notificationText, seenKey, BLINK_PACKAGE, MOTION_TOPIC, HEARTBEAT_TOPIC };
