#!/usr/bin/env node
// Blink notification relay, Termux entry point. See core.js for the why.
//
// Phone setup: docs/BLINK_RELAY_SETUP.md. Env: BLINK_RELAY_BROKER (default mqtt://100.77.44.113:1883,
// the cabin M920q over Tailscale; use the IP, MagicDNS is unreliable in Termux), BLINK_RELAY_DEVICE.
'use strict';

const { execFile } = require('child_process');
const mqtt = require('mqtt');
const { Relay } = require('./core');

const BROKER = process.env.BLINK_RELAY_BROKER || 'mqtt://100.77.44.113:1883';
const DEVICE = process.env.BLINK_RELAY_DEVICE || 'termux-home';
const TICK_MS = 5000;
const HEARTBEAT_MS = 30000;

const log = (m) => console.log(`${new Date().toISOString()} ${m}`);

function listNotifications() {
  return new Promise((resolve, reject) => {
    execFile('termux-notification-list', [], { timeout: 15000, maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      if (err) return reject(err);
      try { resolve(JSON.parse(stdout || '[]')); } catch (e) { reject(e); }
    });
  });
}

const client = mqtt.connect(BROKER, { reconnectPeriod: 5000, connectTimeout: 10000, queueQoSZero: false });
client.on('connect', () => log(`mqtt connected ${BROKER}`));
client.on('error', (e) => log(`mqtt error ${e.message}`));

function publish(topic, payload, opts) {
  return new Promise((resolve, reject) => {
    if (!client.connected) return reject(new Error('mqtt not connected'));
    client.publish(topic, payload, opts, (err) => (err ? reject(err) : resolve()));
  });
}

const relay = new Relay({ listNotifications, publish, device: DEVICE, log });

async function loop(fn, ms, name) {
  for (;;) {
    try { await fn(); } catch (e) { log(`${name}: ${e.message}`); }
    await new Promise((r) => setTimeout(r, ms));
  }
}

loop(() => relay.tick(), TICK_MS, 'tick');
loop(() => relay.heartbeat(), HEARTBEAT_MS, 'heartbeat');
