// cabin-deadman: the off-host half of the cabin dead-man's switch (W-56, docs/CABIN_REACHABILITY.md mechanism A).
//
// The M920q's cabin-recovery-heartbeat service pings this Worker every minute in two kinds:
//   host      "host alive": no dependencies, keeps going while the app is broken
//   platform  "platform healthy": only while cabin-backend and the Zigbee bridge both answered
// A cron trigger (every 5 minutes) looks at how long each kind has been silent and pushes to ntfy.
//
// Routes (every one needs "Authorization: Bearer <HEARTBEAT_TOKEN>"):
//   GET|POST /ping/host        record "host alive" (stored at most every 4 min, KV write limit)   204
//   GET|POST /ping/platform    record "platform healthy"                 204
//   GET|POST /stop/host        the host stopped its heartbeat on purpose 204, and pushes once
//   GET      /status           last-seen ages and open alerts            200 JSON
//
// Secrets (set in the Cloudflare dashboard, never in this repo): HEARTBEAT_TOKEN, NTFY_TOPIC.
// KV binding: HEARTBEAT_KV. Plain vars (wrangler.toml): NTFY_URL, SILENCE_MINUTES, REPEAT_MINUTES.
//
// Rules: a kind that has never pinged is never alerted (an unused platform ping is not nagged). Silence longer than
// SILENCE_MINUTES (default 10) alerts once, then repeats every REPEAT_MINUTES (default 60). While the host kind is
// silent the platform kind is not alerted separately (a dark cabin explains both). When pings resume, one "back"
// message states the outage length.

const KINDS = ['host', 'platform'];
// Workers KV's free tier allows 1000 writes a day. The host pings every minute, so a ping is stored at most once per
// PING_WRITE_MS (well under the 10 minute silence threshold); /status ages are accurate to about that much.
const PING_WRITE_MS = 4 * 60000;
const DEFAULTS = { silenceMinutes: 10, repeatMinutes: 60, ntfyUrl: 'https://ntfy.sh' };

function num(v, d) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : d;
}

function cfg(env) {
  return {
    // Never below two write intervals: pings are stored at most every PING_WRITE_MS, so a smaller threshold would
    // false-alert on a healthy, throttled host.
    silenceMs: Math.max(num(env.SILENCE_MINUTES, DEFAULTS.silenceMinutes) * 60000, 2 * PING_WRITE_MS),
    repeatMs: num(env.REPEAT_MINUTES, DEFAULTS.repeatMinutes) * 60000,
    ntfyUrl: (env.NTFY_URL || DEFAULTS.ntfyUrl).replace(/\/+$/, ''),
  };
}

export function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function minutes(ms) {
  const m = Math.max(1, Math.round(ms / 60000));
  return m < 120 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

async function readInt(kv, key) {
  const v = await kv.get(key);
  const n = v == null ? NaN : Number(v);
  return Number.isFinite(n) ? n : null;
}

async function readAlert(kv, kind) {
  const raw = await kv.get(`alert:${kind}`);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

export async function notify(env, fetchImpl, level, title, message) {
  if (!env.NTFY_TOPIC) return false;
  try {
    const res = await fetchImpl(`${cfg(env).ntfyUrl}/${env.NTFY_TOPIC}`, {
      method: 'POST',
      headers: { Title: title, Priority: level === 'critical' ? 'urgent' : 'default' },
      body: message,
    });
    return !!res && res.ok !== false;
  } catch {
    return false;
  }
}

const LABEL = {
  host: 'host (cabin power, router, Starlink or the M920q)',
  platform: 'platform (cabin-backend or the Zigbee bridge)',
};

// One pass of the cron: decide, per kind, whether to alert, repeat, or say it is back. Exported for the tests.
export async function evaluate(env, now, fetchImpl = fetch) {
  const kv = env.HEARTBEAT_KV;
  const { silenceMs, repeatMs } = cfg(env);
  const hostLast = await readInt(kv, 'last:host');
  const hostSilent = hostLast != null && now - hostLast > silenceMs;
  const sent = [];
  for (const kind of KINDS) {
    const last = await readInt(kv, `last:${kind}`);
    if (last == null) continue;
    const alert = await readAlert(kv, kind);
    const silent = now - last > silenceMs;
    if (alert && last > alert.after) {
      const ok = await notify(env, fetchImpl, 'info', `Cabin ${kind} is back`,
        `The ${LABEL[kind]} heartbeat is back after ${minutes(now - alert.since)}.`);
      if (ok) { await kv.delete(`alert:${kind}`); sent.push(`back:${kind}`); }
      continue;
    }
    if (!silent) continue;
    if (kind === 'platform' && hostSilent) continue;
    if (!alert) {
      const ok = await notify(env, fetchImpl, 'critical', `Cabin ${kind} heartbeat silent`,
        `No ${LABEL[kind]} heartbeat for ${minutes(now - last)}. Last seen ${new Date(last).toISOString()}.`);
      if (ok) {
        await kv.put(`alert:${kind}`, JSON.stringify({ since: last + silenceMs, after: last, notifiedAt: now }));
        sent.push(`silent:${kind}`);
      }
    } else if (now - alert.notifiedAt >= repeatMs) {
      const ok = await notify(env, fetchImpl, 'critical', `Cabin ${kind} heartbeat still silent`,
        `Still no ${LABEL[kind]} heartbeat: silent for ${minutes(now - (alert.since - silenceMs))}.`);
      if (ok) {
        await kv.put(`alert:${kind}`, JSON.stringify({ ...alert, notifiedAt: now }));
        sent.push(`repeat:${kind}`);
      }
    }
  }
  return sent;
}

export async function handleFetch(request, env, now = Date.now(), fetchImpl = fetch) {
  if (!env.HEARTBEAT_TOKEN) return new Response('not configured', { status: 503 });
  const auth = request.headers.get('Authorization') || '';
  if (!safeEqual(auth, `Bearer ${env.HEARTBEAT_TOKEN}`)) return new Response('unauthorized', { status: 401 });
  const path = new URL(request.url).pathname;
  const kv = env.HEARTBEAT_KV;
  const method = request.method;
  if (method !== 'GET' && method !== 'POST') return new Response('method not allowed', { status: 405 });

  const ping = path.match(/^\/ping\/(host|platform)$/);
  if (ping) {
    const key = `last:${ping[1]}`;
    const prev = await readInt(kv, key);
    if (prev == null || now - prev >= PING_WRITE_MS || now < prev) await kv.put(key, String(now));
    return new Response(null, { status: 204 });
  }
  if (path === '/stop/host') {
    // The service stopped on purpose. Say so once, and treat the silence that follows as already announced.
    const last = (await readInt(kv, 'last:host')) ?? now;
    const ok = await notify(env, fetchImpl, 'critical', 'Cabin heartbeat stopped on purpose',
      'The cabin host stopped its heartbeat deliberately. Nothing on the host is watching the components until it is started again.');
    await kv.put('alert:host', JSON.stringify({ since: now, after: Math.max(last, now), notifiedAt: ok ? now : 0 }));
    return new Response(null, { status: 204 });
  }
  if (path === '/status' && method === 'GET') {
    const out = { now: new Date(now).toISOString(), kinds: {} };
    for (const kind of KINDS) {
      const last = await readInt(kv, `last:${kind}`);
      out.kinds[kind] = {
        lastSeen: last == null ? null : new Date(last).toISOString(),
        ageSeconds: last == null ? null : Math.round((now - last) / 1000),
        alert: (await readAlert(kv, kind)) != null,
      };
    }
    return new Response(JSON.stringify(out), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }
  return new Response('not found', { status: 404 });
}

export default {
  fetch(request, env) {
    return handleFetch(request, env);
  },
  scheduled(_event, env, ctx) {
    ctx.waitUntil(evaluate(env, Date.now()));
  },
};
