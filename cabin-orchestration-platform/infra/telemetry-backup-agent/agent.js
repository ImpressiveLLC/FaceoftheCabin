// D19 (Cabin Platform Decisions artifact), Option B -- independent MQTT
// log-shipping side-car.
//
// Why this exists: cabin_event's live/hot tier has exactly one copy
// (cabin-postgres, on the M920q's SSD-backed root filesystem) -- see
// TelemetryArchivalService's monthly export, which only ever touches rows
// already 3+ months old. The 2026-09-13 Zigbee mesh outage's ~34-hour data
// gap was only recoverable because Zigbee2MQTT's own `docker logs` happened
// to retain the whole window -- a lucky side effect of a different
// service's log retention, not a designed recovery path, and it only
// covered Zigbee, not every topic cabin-backend consumes.
//
// This process subscribes directly to the same broker cabin-backend does
// and appends every message it sees, verbatim, to a local file -- with no
// dependency on cabin-backend's own health at all. If cabin-backend itself
// is the thing silently broken (exactly what happened in the incident this
// exists to answer), this side-car keeps recording anyway, since it never
// talks to cabin-backend, only to mosquitto.
//
// Deliberately NOT a scoped subset of topics: the incident's whole lesson
// was "we got lucky it was Zigbee's logs that survived" -- narrowing this
// to a hand-picked topic list would just move the same luck-dependency
// somewhere else. Subscribing to every real top-level prefix this project's
// own MQTT topic contract documents (see CLAUDE.md) costs nothing extra at
// this message volume and needs no maintenance when a new topic is added
// elsewhere in the codebase.

const fs = require("fs");
const path = require("path");
const mqtt = require("mqtt");

const MQTT_URL = process.env.MQTT_URL || "mqtt://mosquitto:1883";
const OUT_DIR = process.env.OUT_DIR || "/app/telemetry-backup";
const RETENTION_DAYS = Number(process.env.RETENTION_DAYS || 30);
const TOPICS = ["cabin/#", "home/#", "zigbee2mqtt/#", "home_z2m/#"];

fs.mkdirSync(OUT_DIR, { recursive: true });

let currentDay = null;
let currentStream = null;

// One growing file per UTC calendar day, matching TelemetryArchivalService's
// own UTC-boundary convention -- rotation is just "did the day change since
// the last message," checked per-write rather than on a timer, so a quiet
// broker never needs a background tick just to roll the file at midnight.
function streamForNow() {
  const day = new Date().toISOString().slice(0, 10); // YYYY-MM-DD
  if (day !== currentDay) {
    if (currentStream) currentStream.end();
    currentDay = day;
    const file = path.join(OUT_DIR, `mqtt-raw-${day}.jsonl`);
    currentStream = fs.createWriteStream(file, { flags: "a" });
    console.log(`[telemetry-backup-agent] writing to ${file}`);
  }
  return currentStream;
}

function appendMessage(topic, payloadBuffer) {
  const raw = payloadBuffer.toString("utf8");
  let payload = raw;
  try {
    payload = JSON.parse(raw);
  } catch {
    // Plenty of real topics on this broker are plain strings (presence,
    // armed/disarmed, simple on/off) -- not every payload is JSON, and
    // that's not an error condition worth logging per-message.
  }
  const line = JSON.stringify({ receivedAt: new Date().toISOString(), topic, payload }) + "\n";
  streamForNow().write(line);
}

// Runs once at startup and once every 24h -- deletes any file whose
// embedded date has aged out of RETENTION_DAYS. Filename-based, not
// mtime-based, so a file this process only appended to once weeks ago
// (a quiet topic) is still judged by the day it actually records, not by
// when it happened to last be touched.
function pruneOldFiles() {
  const cutoff = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;
  let files;
  try {
    files = fs.readdirSync(OUT_DIR);
  } catch (e) {
    console.error(`[telemetry-backup-agent] could not list ${OUT_DIR}: ${e.message}`);
    return;
  }
  for (const name of files) {
    const match = /^mqtt-raw-(\d{4}-\d{2}-\d{2})\.jsonl$/.exec(name);
    if (!match) continue;
    const fileDay = Date.parse(match[1] + "T00:00:00Z");
    if (!Number.isNaN(fileDay) && fileDay < cutoff) {
      const full = path.join(OUT_DIR, name);
      try {
        fs.unlinkSync(full);
        console.log(`[telemetry-backup-agent] pruned ${name} (older than ${RETENTION_DAYS}d)`);
      } catch (e) {
        console.error(`[telemetry-backup-agent] failed to prune ${name}: ${e.message}`);
      }
    }
  }
}

function connect() {
  const client = mqtt.connect(MQTT_URL, {
    clientId: `telemetry-backup-agent-${Math.random().toString(16).slice(2, 10)}`,
    reconnectPeriod: 5000,
  });

  client.on("connect", () => {
    console.log(`[telemetry-backup-agent] connected to ${MQTT_URL}`);
    client.subscribe(TOPICS, { qos: 0 }, (err) => {
      if (err) console.error(`[telemetry-backup-agent] subscribe failed: ${err.message}`);
      else console.log(`[telemetry-backup-agent] subscribed: ${TOPICS.join(", ")}`);
    });
  });

  client.on("message", (topic, payload) => {
    try {
      appendMessage(topic, payload);
    } catch (e) {
      console.error(`[telemetry-backup-agent] failed to append message on ${topic}: ${e.message}`);
    }
  });

  // The `mqtt` client library already auto-reconnects (reconnectPeriod
  // above) -- this only logs so a flapping broker connection is visible
  // in `docker logs`, not silent.
  client.on("reconnect", () => console.log("[telemetry-backup-agent] reconnecting..."));
  client.on("error", (e) => console.error(`[telemetry-backup-agent] mqtt error: ${e.message}`));
  client.on("close", () => console.log("[telemetry-backup-agent] connection closed"));
}

pruneOldFiles();
setInterval(pruneOldFiles, 24 * 60 * 60 * 1000);
connect();

process.on("SIGTERM", () => {
  if (currentStream) currentStream.end();
  process.exit(0);
});
