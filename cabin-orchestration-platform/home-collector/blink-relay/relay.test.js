'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { Relay, cameraFor, MOTION_TOPIC, HEARTBEAT_TOPIC } = require('./core');

const blink = (title, extra = {}) => ({ packageName: 'com.immediasemi.android.blink', id: 1, key: 'k1', title, content: 'null', when: '2026-10-24 23:19:00', ...extra });

function make(listFn) {
  const published = [];
  let t = Date.parse('2026-10-24T23:19:05');
  const relay = new Relay({
    listNotifications: listFn,
    publish: async (topic, payload, o) => { published.push({ topic, payload, o }); },
    now: () => t,
  });
  return { relay, published, advance: (ms) => { t += ms; } };
}

test('camera mapping matches the HA automation', () => {
  assert.equal(cameraFor('Motion detected at your AldrichFront.'), 'home_aldrich_front');
  assert.equal(cameraFor('Motion detected at your Outdoor 4 - DHEE'), 'driveway');
  assert.equal(cameraFor('Battery low'), null);
});

test('notifications already in the tray at startup are never replayed', async () => {
  const { relay, published } = make(async () => [blink('Motion detected at your AldrichFront.')]);
  await relay.tick();
  await relay.tick();
  assert.equal(published.length, 0);
});

test('new motion notification publishes once, unretained, to cabin/blink/motion', async () => {
  let tray = [];
  const { relay, published } = make(async () => tray);
  await relay.tick();  // prime
  tray = [blink('Motion detected at your AldrichFront.')];
  assert.deepEqual(await relay.tick(), ['home_aldrich_front']);
  await relay.tick();  // same notification still in tray
  assert.equal(published.length, 1);
  assert.deepEqual(published[0], { topic: MOTION_TOPIC, payload: 'home_aldrich_front', o: { qos: 1, retain: false } });
});

test('a second alert replacing the first (new when) publishes again', async () => {
  let tray = [];
  const { relay, published, advance } = make(async () => tray);
  await relay.tick();
  tray = [blink('Motion detected at your AldrichFront.')];
  await relay.tick();
  advance(1000);
  tray = [blink('Motion detected at your AldrichFront.', { when: '2026-10-24 23:19:06' })];
  await relay.tick();
  assert.equal(published.length, 2);
});

test('stale tray entries and other apps are ignored', async () => {
  let tray = [];
  const { relay, published } = make(async () => tray);
  await relay.tick();
  tray = [
    blink('Motion detected at your AldrichFront.', { when: '2026-10-24 22:00:00', key: 'old' }),
    { packageName: 'com.other', title: 'Motion detected at your AldrichFront.', key: 'x', when: '2026-10-24 23:19:00' },
  ];
  await relay.tick();
  assert.equal(published.length, 0);
});

test('failed publish is retried on the next tick, not lost', async () => {
  let tray = [];
  let fail = true;
  const published = [];
  const t = Date.parse('2026-10-24T23:19:05');
  const relay = new Relay({
    listNotifications: async () => tray,
    publish: async (topic, payload) => { if (fail) throw new Error('mqtt down'); published.push(payload); },
    now: () => t,
  });
  await relay.tick();
  tray = [blink('Motion detected at your Outdoor 4 - DHEE')];
  await relay.tick();
  assert.equal(published.length, 0);
  fail = false;
  await relay.tick();
  assert.deepEqual(published, ['driveway']);
});

test('heartbeat is retained and only sent while the tray is readable', async () => {
  let readable = true;
  const { relay, published, advance } = make(async () => { if (!readable) throw new Error('permission denied'); return []; });
  assert.equal(await relay.heartbeat(), false);  // never read yet
  await relay.tick();
  assert.equal(await relay.heartbeat(), true);
  assert.equal(published[0].topic, HEARTBEAT_TOPIC);
  assert.equal(published[0].o.retain, true);
  assert.ok(JSON.parse(published[0].payload).ts);
  readable = false;
  await assert.rejects(relay.tick());  // notification access lost
  advance(61000);
  assert.equal(await relay.heartbeat(), false);  // goes quiet -> backend alerts
});
