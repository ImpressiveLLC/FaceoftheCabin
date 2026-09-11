#!/usr/bin/env node
// Home network scan agent.
//
// Runs on the Home Termux phone, not on cabin-backend -- mDNS is
// link-local multicast, it cannot cross Tailscale from the cabin M920q
// to Home's physical LAN. This is the only thing that actually has a
// presence on Home's WiFi network, the same reason Zigbee2MQTT runs here
// too. See docs/MAINTENANCE.md's Home Location section.
//
// Protocol (mirrors Zigbee2MqttAdapter's permit_join / bridge/devices
// shape, on its own topic family so it can never collide with Z2M's):
//   subscribe  home/network-scan/request  {"enable":true,"durationSeconds":254} | {"enable":false}
//   publish    home/network-scan/results  one message per device found, e.g.
//                {"name":"Living Room TV","host":"livingroomtv.local",
//                 "address":"192.168.1.50","port":8008,"type":"_googlecast._tcp.local"}
//
// Records accumulate across the whole scan window and are resolved once,
// at the end, rather than trying to correlate PTR/SRV/A/TXT per-packet --
// mDNS responders commonly split these across separate packets, and
// waiting for the full window is simpler and more robust than assuming a
// particular arrival order.

const mqtt = require('mqtt');
const mdns = require('multicast-dns')();

const BROKER_URL = process.env.MQTT_BROKER_URL || 'mqtt://100.77.44.113:1883';
const REQUEST_TOPIC = 'home/network-scan/request';
const RESULTS_TOPIC = 'home/network-scan/results';

// A curated list, not an attempt at "literally everything" -- common
// smart-home/consumer service types most likely to matter here. Easy to
// extend later; this is deliberately not the harder-to-get-right generic
// _services._dns-sd._udp.local browse-then-browse-again dance.
const SERVICE_TYPES = [
  '_googlecast._tcp.local',   // Chromecast, Google Home, Android TV
  '_hap._tcp.local',          // HomeKit accessories
  '_airplay._tcp.local',      // Apple TV, AirPlay speakers
  '_hue._tcp.local',          // Philips Hue bridge
  '_matter._tcp.local',       // Matter/Thread devices and border routers
  '_esphomelib._tcp.local',   // ESPHome (common DIY/enthusiast IoT)
  '_spotify-connect._tcp.local',
  '_ipp._tcp.local',          // network printers
  '_workstation._tcp.local',  // general computers/NAS
  '_smb._tcp.local',          // file shares/NAS
  '_ssh._tcp.local',
  '_http._tcp.local',         // generic web-UI devices -- many IoT expose one
];

let scanTimer = null;
// instanceName -> { type }
let ptrByInstance = new Map();
// instanceName -> { host, port }
let srvByInstance = new Map();
// hostname -> address
let addressByHost = new Map();
// instanceName -> txt object
let txtByInstance = new Map();

function resetScanState() {
  ptrByInstance = new Map();
  srvByInstance = new Map();
  addressByHost = new Map();
  txtByInstance = new Map();
}

function recordAnswer(a) {
  if (!a || !a.type) return;
  if (a.type === 'PTR' && a.data && SERVICE_TYPES.some(t => a.name === t || a.name.startsWith('_'))) {
    // a.data is the instance name, e.g. "Living Room TV._googlecast._tcp.local"
    if (!ptrByInstance.has(a.data)) ptrByInstance.set(a.data, { type: a.name });
  } else if (a.type === 'SRV' && a.data) {
    srvByInstance.set(a.name, { host: a.data.target, port: a.data.port });
  } else if (a.type === 'A' && a.data) {
    addressByHost.set(a.name, a.data);
  } else if (a.type === 'TXT' && a.data) {
    try {
      const txt = {};
      for (const buf of a.data) {
        const s = buf.toString('utf8');
        const eq = s.indexOf('=');
        if (eq > 0) txt[s.slice(0, eq)] = s.slice(eq + 1);
      }
      txtByInstance.set(a.name, txt);
    } catch (e) { /* malformed TXT, ignore */ }
  }
}

mdns.on('response', (response) => {
  (response.answers || []).forEach(recordAnswer);
  (response.additionals || []).forEach(recordAnswer);
});

function startScan(durationSeconds) {
  console.log(`[network-scan-agent] scan starting, duration=${durationSeconds}s`);
  resetScanState();
  SERVICE_TYPES.forEach((type) => {
    mdns.query({ questions: [{ name: type, type: 'PTR' }] });
  });
  scanTimer = setTimeout(() => finishScan(), durationSeconds * 1000);
}

function stopScanEarly() {
  if (scanTimer) {
    clearTimeout(scanTimer);
    finishScan();
  }
}

function finishScan() {
  scanTimer = null;
  let found = 0;
  for (const [instanceName, { type }] of ptrByInstance) {
    const srv = srvByInstance.get(instanceName);
    if (!srv) continue; // never got a SRV record for this instance -- can't publish host/port
    const address = addressByHost.get(srv.host);
    const result = {
      name: instanceName.split('.')[0],
      host: srv.host,
      address: address || null,
      port: srv.port,
      type,
    };
    const txt = txtByInstance.get(instanceName);
    if (txt) result.txt = txt;
    if (!address) {
      console.log(`[network-scan-agent] found ${result.name} (${type}) but no A record yet -- publishing without address`);
    }
    client.publish(RESULTS_TOPIC, JSON.stringify(result));
    found++;
  }
  console.log(`[network-scan-agent] scan finished, ${found} device(s) published`);
}

const client = mqtt.connect(BROKER_URL, { clientId: 'home-network-scan-agent' });

client.on('connect', () => {
  console.log(`[network-scan-agent] connected to ${BROKER_URL}`);
  client.subscribe(REQUEST_TOPIC, { qos: 1 });
});

client.on('reconnect', () => console.log('[network-scan-agent] reconnecting...'));
client.on('error', (err) => console.error('[network-scan-agent] MQTT error:', err.message));

client.on('message', (topic, payload) => {
  if (topic !== REQUEST_TOPIC) return;
  try {
    const body = JSON.parse(payload.toString());
    if (body.enable) {
      startScan(body.durationSeconds || 254);
    } else {
      stopScanEarly();
    }
  } catch (e) {
    console.error('[network-scan-agent] failed to parse request:', e.message);
  }
});
