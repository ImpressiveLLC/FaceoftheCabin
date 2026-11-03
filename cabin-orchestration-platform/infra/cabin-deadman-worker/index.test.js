import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import worker, { evaluate, handleFetch, safeEqual } from './src/index.js';

// Fake KV namespace and fake ntfy: no network, no Cloudflare.
const MIN = 60000;
const T0 = Date.parse('2026-11-03T12:00:00Z');

function kv() {
  const m = new Map();
  return { m, get: async k => (m.has(k) ? m.get(k) : null), put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); } };
}
function world(extra = {}) {
  const calls = [];
  let fail = false;
  const env = { HEARTBEAT_TOKEN: 'tok', NTFY_TOPIC: 'topic-x', HEARTBEAT_KV: kv(), ...extra };
  const fetchImpl = async (url, init) => {
    if (fail) throw new Error('down');
    calls.push({ url, title: init.headers.Title, priority: init.headers.Priority, body: init.body });
    return { ok: true };
  };
  return { env, calls, fetchImpl, setFail: v => { fail = v; } };
}
const req = (path, { method = 'POST', token = 'tok' } = {}) =>
  new Request(`https://cabin-deadman.example.workers.dev${path}`, { method, headers: token == null ? {} : { Authorization: `Bearer ${token}` } });

test('rejects a missing or wrong token and refuses to run unconfigured', async () => {
  const w = world();
  assert.equal((await handleFetch(req('/ping/host', { token: null }), w.env, T0)).status, 401);
  assert.equal((await handleFetch(req('/ping/host', { token: 'nope' }), w.env, T0)).status, 401);
  assert.equal(w.env.HEARTBEAT_KV.m.size, 0);
  assert.equal((await handleFetch(req('/ping/host'), { ...w.env, HEARTBEAT_TOKEN: undefined }, T0)).status, 503);
});

test('records both ping kinds and serves status; unknown routes and methods are refused', async () => {
  const w = world();
  assert.equal((await handleFetch(req('/ping/host'), w.env, T0)).status, 204);
  assert.equal((await handleFetch(req('/ping/platform', { method: 'GET' }), w.env, T0 + 30000)).status, 204);
  const status = await (await handleFetch(req('/status', { method: 'GET' }), w.env, T0 + 60000)).json();
  assert.equal(status.kinds.host.ageSeconds, 60);
  assert.equal(status.kinds.platform.ageSeconds, 30);
  assert.equal(status.kinds.host.alert, false);
  assert.equal((await handleFetch(req('/ping/other'), w.env, T0)).status, 404);
  assert.equal((await handleFetch(req('/ping/host', { method: 'DELETE' }), w.env, T0)).status, 405);
  assert.equal((await handleFetch(req('/status'), w.env, T0)).status, 404); // POST /status is not a route
});

test('never alerts on a kind that has not pinged, and stays quiet while pings are fresh', async () => {
  const w = world();
  assert.deepEqual(await evaluate(w.env, T0, w.fetchImpl), []);
  await handleFetch(req('/ping/host'), w.env, T0);
  assert.deepEqual(await evaluate(w.env, T0 + 9 * MIN, w.fetchImpl), []);
  assert.equal(w.calls.length, 0);
});

test('silence over ten minutes alerts once, repeats hourly, and says it is back with the outage length', async () => {
  const w = world();
  await handleFetch(req('/ping/host'), w.env, T0);
  assert.deepEqual(await evaluate(w.env, T0 + 11 * MIN, w.fetchImpl), ['silent:host']);
  assert.equal(w.calls[0].priority, 'urgent');
  assert.equal(w.calls[0].url, 'https://ntfy.sh/topic-x');
  assert.match(w.calls[0].body, /No host .* heartbeat for 11 min/);
  assert.deepEqual(await evaluate(w.env, T0 + 16 * MIN, w.fetchImpl), []); // next cron: no repeat
  assert.deepEqual(await evaluate(w.env, T0 + 71 * MIN, w.fetchImpl), ['repeat:host']);
  assert.equal(w.calls.length, 2);
  await handleFetch(req('/ping/host'), w.env, T0 + 80 * MIN);
  assert.deepEqual(await evaluate(w.env, T0 + 81 * MIN, w.fetchImpl), ['back:host']);
  assert.equal(w.calls[2].priority, 'default');
  assert.match(w.calls[2].body, /back after 71 min/);
  assert.deepEqual(await evaluate(w.env, T0 + 90 * MIN, w.fetchImpl), []); // and it does not say it twice
});

test('platform silence alone is its own alert; a silent host explains the platform and suppresses it', async () => {
  const w = world();
  await handleFetch(req('/ping/host'), w.env, T0);
  await handleFetch(req('/ping/platform'), w.env, T0);
  await handleFetch(req('/ping/host'), w.env, T0 + 14 * MIN);
  assert.deepEqual(await evaluate(w.env, T0 + 15 * MIN, w.fetchImpl), ['silent:platform']);
  assert.match(w.calls[0].body, /platform \(cabin-backend or the Zigbee bridge\)/);

  const d = world();
  await handleFetch(req('/ping/host'), d.env, T0);
  await handleFetch(req('/ping/platform'), d.env, T0);
  assert.deepEqual(await evaluate(d.env, T0 + 15 * MIN, d.fetchImpl), ['silent:host']);
  assert.equal(d.calls.length, 1);
});

test('a failed ntfy send is retried on the next cron instead of being lost', async () => {
  const w = world();
  await handleFetch(req('/ping/host'), w.env, T0);
  w.setFail(true);
  assert.deepEqual(await evaluate(w.env, T0 + 11 * MIN, w.fetchImpl), []);
  assert.equal(w.env.HEARTBEAT_KV.m.has('alert:host'), false);
  w.setFail(false);
  assert.deepEqual(await evaluate(w.env, T0 + 16 * MIN, w.fetchImpl), ['silent:host']);
});

test('a missing topic never throws and never records an alert as sent', async () => {
  const w = world({ NTFY_TOPIC: undefined });
  await handleFetch(req('/ping/host'), w.env, T0);
  assert.deepEqual(await evaluate(w.env, T0 + 30 * MIN, w.fetchImpl), []);
  assert.equal(w.calls.length, 0);
});

test('a deliberate stop pushes once, is not announced again as silence, and clears when pings resume', async () => {
  const w = world();
  await handleFetch(req('/ping/host'), w.env, T0);
  assert.equal((await handleFetch(req('/stop/host'), w.env, T0 + MIN, w.fetchImpl)).status, 204);
  assert.equal(w.calls.length, 1);
  assert.equal(w.calls[0].title, 'Cabin heartbeat stopped on purpose');
  assert.deepEqual(await evaluate(w.env, T0 + 5 * MIN, w.fetchImpl), []);   // fresh-looking, alert open, no "back"
  assert.deepEqual(await evaluate(w.env, T0 + 20 * MIN, w.fetchImpl), []);  // silent but already announced
  assert.equal(w.calls.length, 1);
  assert.deepEqual(await evaluate(w.env, T0 + 61 * MIN, w.fetchImpl), ['repeat:host']);
  await handleFetch(req('/ping/host'), w.env, T0 + 70 * MIN);
  assert.deepEqual(await evaluate(w.env, T0 + 71 * MIN, w.fetchImpl), ['back:host']);
});

test('pings are stored at most once per four minutes so the KV free tier is not exhausted', async () => {
  const w = world();
  let puts = 0;
  const realPut = w.env.HEARTBEAT_KV.put;
  w.env.HEARTBEAT_KV.put = async (k, v) => { if (k.startsWith('last:')) puts++; return realPut(k, v); };
  for (let i = 0; i < 10; i++) await handleFetch(req('/ping/host'), w.env, T0 + i * 60000);   // ten minutes, one ping a minute
  assert.equal(puts, 3);                                                                       // minutes 0, 4 and 8
  const status = await (await handleFetch(req('/status', { method: 'GET' }), w.env, T0 + 9 * 60000)).json();
  assert.equal(status.kinds.host.ageSeconds, 60);                                              // minute 8's write
  assert.deepEqual(await evaluate(w.env, T0 + 9 * MIN, w.fetchImpl), []);
  // a day of pings stays under the 1000-writes-a-day limit for both kinds together
  const d = world();
  let dayPuts = 0;
  const dayPut = d.env.HEARTBEAT_KV.put;
  d.env.HEARTBEAT_KV.put = async (k, v) => { dayPuts++; return dayPut(k, v); };
  for (let i = 0; i < 1440; i++) {
    await handleFetch(req('/ping/host'), d.env, T0 + i * 60000);
    await handleFetch(req('/ping/platform'), d.env, T0 + i * 60000);
  }
  assert.ok(dayPuts <= 720, `${dayPuts} writes in a day`);
});

test('a ping after a long silence is stored at once, and clock going backwards still stores', async () => {
  const w = world();
  await handleFetch(req('/ping/host'), w.env, T0);
  await handleFetch(req('/ping/host'), w.env, T0 + 60 * MIN);
  assert.equal(await w.env.HEARTBEAT_KV.get('last:host'), String(T0 + 60 * MIN));
  await handleFetch(req('/ping/host'), w.env, T0 + 59 * MIN);
  assert.equal(await w.env.HEARTBEAT_KV.get('last:host'), String(T0 + 59 * MIN));
});

test('thresholds come from the variables', async () => {
  const w = world({ SILENCE_MINUTES: '3', REPEAT_MINUTES: '5', NTFY_URL: 'https://ntfy.example/' });
  await handleFetch(req('/ping/host'), w.env, T0);
  assert.deepEqual(await evaluate(w.env, T0 + 4 * MIN, w.fetchImpl), ['silent:host']);
  assert.equal(w.calls[0].url, 'https://ntfy.example/topic-x');
  assert.deepEqual(await evaluate(w.env, T0 + 10 * MIN, w.fetchImpl), ['repeat:host']);
});

test('safeEqual compares whole strings', () => {
  assert.equal(safeEqual('Bearer a', 'Bearer a'), true);
  assert.equal(safeEqual('Bearer a', 'Bearer b'), false);
  assert.equal(safeEqual('Bearer a', 'Bearer ab'), false);
  assert.equal(safeEqual(undefined, 'x'), false);
});

test('the module exports the Worker entry points', () => {
  assert.equal(typeof worker.fetch, 'function');
  assert.equal(typeof worker.scheduled, 'function');
});

test('wrangler.toml names the contract: Worker, cron, KV binding, and holds no secret value', () => {
  const toml = readFileSync(new URL('./wrangler.toml', import.meta.url), 'utf8');
  assert.match(toml, /^name = "cabin-deadman"/m);
  assert.match(toml, /crons = \["\*\/5 \* \* \* \*"\]/);
  assert.match(toml, /binding = "HEARTBEAT_KV"/);
  assert.doesNotMatch(toml, /HEARTBEAT_TOKEN\s*=|NTFY_TOPIC\s*=/);
});
