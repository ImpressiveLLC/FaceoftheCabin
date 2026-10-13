// W-33: tests for the intrusion flow's security gate and presence heartbeat.
//
// These run the function-node code that is actually in flows.json (not a copy),
// so what is tested is exactly what would be deployed. Run from this directory:
//     node --test test/security-gate.test.js
// No dependencies; needs Node 20+.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const FLOWS = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'flows.json'), 'utf8'));
const byId = Object.fromEntries(FLOWS.map((n) => [n.id, n]));
const HOUR = 3600 * 1000;
const NOW = Date.parse('2026-10-04T12:00:00Z');

/** Runs a function node's code the way Node-RED does, with a fixed clock and in-memory flow context. */
function run(nodeId, msg, { flow = {}, env = {}, now = NOW } = {}) {
  const store = new Map(Object.entries(flow));
  const statuses = [];
  class FakeDate extends Date {
    constructor(...args) { if (args.length) super(...args); else super(now); }
    static now() { return now; }
  }
  const sandbox = {
    msg,
    flow: { get: (k) => store.get(k), set: (k, v) => { store.set(k, v); } },
    node: { status: (s) => statuses.push(s) },
    env: { get: (k) => env[k] },
    Date: FakeDate,
    JSON, Number, String, Math, Promise, isNaN, Object,
  };
  const fn = vm.runInNewContext(`(function(){${byId[nodeId].func}\n})`, sandbox);
  return { out: fn(), store, statuses };
}

const door = { door: 'front', detectedAt: '2026-10-04T11:59:55Z' };
const armedAway = { automationEnabled: true, armedAway: true, natePresence: 'not_home' };
const fresh = (hoursAgo) => ({ presenceSeenAt: NOW - hoursAgo * HOUR });
const gate = (flow, env) => {
  const { out, store } = run('security-gate', { ...door }, { flow, env });
  const [alarm, audit, push] = out;
  return { alarm, audit: JSON.parse(audit.payload), push: push && { topic: push.topic, body: JSON.parse(push.payload) }, store };
};

test('automation disabled blocks everything and sends nothing', () => {
  const r = gate({ ...armedAway, automationEnabled: false, ...fresh(1) });
  assert.equal(r.audit.decision, 'blocked');
  assert.equal(r.audit.reason, 'automation disabled');
  assert.equal(r.alarm, null);
  assert.equal(r.push, null);
});

test('disarmed blocks even when presence is stale (no push, nothing to alarm about)', () => {
  const r = gate({ ...armedAway, armedAway: false, ...fresh(50) });
  assert.equal(r.audit.reason, 'system disarmed');
  assert.equal(r.alarm, null);
  assert.equal(r.push, null);
});

test('armed + not_home + fresh heartbeat is an alarm candidate (siren path unchanged)', () => {
  const r = gate({ ...armedAway, ...fresh(1) });
  assert.equal(r.audit.decision, 'alarm_candidate');
  assert.notEqual(r.alarm, null, 'output 1 carries the message that drives the sirens');
  assert.equal(r.push, null);
  assert.equal(r.audit.presenceStale, false);
  assert.equal(r.audit.presenceAgeHours, 1);
});

test('no heartbeat ever seen: the staleness rule is inactive, behaviour is exactly as before', () => {
  const r = gate({ ...armedAway });
  assert.equal(r.audit.decision, 'alarm_candidate');
  assert.equal(r.audit.presenceAgeHours, null);
  assert.equal(r.audit.presenceStale, false);
  assert.equal(r.push, null);
});

test('armed + not_home + heartbeat older than 6 h: push only, NO siren', () => {
  const r = gate({ ...armedAway, ...fresh(7) });
  assert.equal(r.audit.decision, 'stale_push_only');
  assert.equal(r.alarm, null, 'output 1 must be empty so alarm-on never fires');
  assert.equal(r.audit.presenceStale, true);
  assert.equal(r.audit.presenceAgeHours, 7);
  assert.equal(r.push.topic, 'cabin/event/critical', 'a CRITICAL hub event is what becomes the ntfy push');
  assert.equal(r.push.body.event, 'INTRUSION_CANDIDATE_STALE_PRESENCE');
  assert.equal(r.push.body.sirenSounded, false);
  assert.equal(r.push.body.door, 'front');
  assert.equal(r.push.body.staleLimitHours, 6);
});

test('stale is checked before presence, so a stale "home" also pushes instead of trusting it', () => {
  const r = gate({ ...armedAway, natePresence: 'home', ...fresh(12) });
  assert.equal(r.audit.decision, 'stale_push_only');
  assert.equal(r.alarm, null);
  assert.notEqual(r.push, null);
});

test('fresh "home" still blocks with "Nate is home"', () => {
  const r = gate({ ...armedAway, natePresence: 'home', ...fresh(1) });
  assert.equal(r.audit.reason, 'Nate is home');
  assert.equal(r.alarm, null);
  assert.equal(r.push, null);
});

test('unknown presence with a fresh heartbeat still fails safe', () => {
  const r = gate({ ...armedAway, natePresence: 'unknown', ...fresh(1) });
  assert.match(r.audit.reason, /presence unknown/);
  assert.equal(r.alarm, null);
});

test('exactly at the limit is not stale; just past it is', () => {
  assert.equal(gate({ ...armedAway, ...fresh(6) }).audit.decision, 'alarm_candidate');
  assert.equal(gate({ ...armedAway, presenceSeenAt: NOW - 6 * HOUR - 1000 }).audit.decision, 'stale_push_only');
});

test('PRESENCE_STALE_HOURS overrides the limit; junk values fall back to 6', () => {
  assert.equal(gate({ ...armedAway, ...fresh(7) }, { PRESENCE_STALE_HOURS: '12' }).audit.decision, 'alarm_candidate');
  const r = gate({ ...armedAway, ...fresh(13) }, { PRESENCE_STALE_HOURS: '12' });
  assert.equal(r.audit.decision, 'stale_push_only');
  assert.equal(r.audit.staleLimitHours, 12);
  assert.equal(gate({ ...armedAway, ...fresh(7) }, { PRESENCE_STALE_HOURS: 'abc' }).audit.staleLimitHours, 6);
  assert.equal(gate({ ...armedAway, ...fresh(7) }, { PRESENCE_STALE_HOURS: '-3' }).audit.staleLimitHours, 6);
});

test('stale pushes are rate limited to one per 10 minutes', () => {
  const first = gate({ ...armedAway, ...fresh(8) });
  assert.notEqual(first.push, null);
  const second = gate({ ...armedAway, ...fresh(8), lastStalePushAt: NOW - 5 * 60 * 1000 });
  assert.equal(second.push, null);
  assert.equal(second.audit.decision, 'blocked');
  assert.match(second.audit.reason, /push cooldown/);
  assert.notEqual(gate({ ...armedAway, ...fresh(8), lastStalePushAt: NOW - 11 * 60 * 1000 }).push, null);
});

test('the existing 10 minute siren cooldown is unchanged', () => {
  const r = gate({ ...armedAway, ...fresh(1), lastAlarmAt: NOW - 5 * 60 * 1000 });
  assert.equal(r.audit.reason, '10 minute cooldown active');
  assert.equal(r.alarm, null);
});

test('a stale event does not start the siren cooldown', () => {
  const r = gate({ ...armedAway, ...fresh(8) });
  assert.equal(r.store.get('lastAlarmAt'), undefined);
});

test('heartbeat node: a valid ISO timestamp is remembered and shown as an age', () => {
  const { store, statuses } = run('presence-seen-state', { payload: '2026-10-04T09:00:00Z' });
  assert.equal(store.get('presenceSeenAt'), Date.parse('2026-10-04T09:00:00Z'));
  assert.match(statuses[0].text, /phone seen 3\.0 h ago/);
  assert.equal(statuses[0].fill, 'green');
});

test('heartbeat node: an old timestamp shows yellow; junk is rejected and changes nothing', () => {
  assert.equal(run('presence-seen-state', { payload: '2026-10-03T12:00:00Z' }).statuses[0].fill, 'yellow');
  for (const bad of ['', 'not a date', 'null']) {
    const { store, statuses } = run('presence-seen-state', { payload: bad }, { flow: { presenceSeenAt: 123 } });
    assert.equal(store.get('presenceSeenAt'), 123, `"${bad}" must not overwrite the last good heartbeat`);
    assert.equal(statuses[0].fill, 'red');
  }
});

test('wiring: gate has three outputs, the siren path is untouched, the push goes to its own output', () => {
  const g = byId['security-gate'];
  assert.equal(g.outputs, 3);
  assert.deepEqual(g.wires[0], ['alarm-on']);
  assert.deepEqual(g.wires[1], ['audit-out', 'audit-debug']);
  assert.deepEqual(g.wires[2], ['push-alert-out']);
  assert.equal(byId['push-alert-out'].type, 'mqtt out');
  assert.equal(byId['push-alert-out'].topic, '', 'topic comes from msg.topic');
  assert.equal(byId['alarm-on'].wires[0][0], 'alarm-state');
});

test('wiring: the heartbeat topic is subscribed exactly and feeds the remember node', () => {
  assert.equal(byId['presence-seen-in'].topic, 'cabin/presence/nate/last_seen');
  assert.deepEqual(byId['presence-seen-in'].wires, [['presence-seen-state']]);
  assert.equal(byId['presence-in'].topic, 'cabin/presence/nate', 'the presence value topic must stay exact');
  const wildcard = FLOWS.filter((n) => n.type === 'mqtt in' && /[#+]/.test(n.topic || ''));
  assert.deepEqual(wildcard, [], 'no wildcard subscription may swallow the heartbeat as a presence value');
});

test('flow integrity: every wire target exists and every node is on the security tab', () => {
  for (const n of FLOWS) {
    for (const out of n.wires || []) for (const target of out) assert.ok(byId[target], `${n.id} wires to missing node ${target}`);
    if (n.type !== 'tab' && n.type !== 'mqtt-broker') assert.equal(n.z, 'cabin-security-tab', `${n.id} is on the wrong tab`);
    if (n.type === 'mqtt in' || n.type === 'mqtt out') assert.equal(byId[n.broker]?.type, 'mqtt-broker', `${n.id} references a missing broker`);
  }
  const ids = FLOWS.map((n) => n.id);
  assert.equal(new Set(ids).size, ids.length, 'duplicate node ids');
});

test('the live siren outputs stay enabled and only the gate can reach them', () => {
  assert.notEqual(byId['live-siren-on'].d, true, 'the siren-on output is enabled live; this change must not disable (or silently enable) anything');
  assert.notEqual(byId['live-siren-off'].d, true);
  const feeders = FLOWS.filter((n) => (n.wires || []).some((out) => out.includes('alarm-on'))).map((n) => n.id);
  assert.deepEqual(feeders, ['security-gate']);
});
