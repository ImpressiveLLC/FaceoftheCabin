// Run from this directory with no dependencies:  node --test generate-presence.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { allowlist, displayName, generate, loadTemplates, parseSeed, render, stripSqlComments, validateRoster } from "./generate-presence.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const haDir = path.resolve(here, "..");
const seedPath = path.resolve(here, "../../../../../scripts/host/w38-register-primary-presence.sql");
const lf = (t) => t.replace(/\r\n/g, "\n");

const NATE = { personId: "nate", location: "cabin", zone: "zone.cabin", gpsTracker: "device_tracker.nates_s23", batterySensor: "sensor.nates_s23_battery_level" };
const CHILD = { personId: "child_1", location: "cabin", zone: "zone.cabin", gpsTracker: "device_tracker.child_1_phone", batterySensor: "sensor.child_1_phone_battery_level" };
const seedWith = (...rows) => parseSeed(rows.map(([p, l, r]) => `INSERT INTO tracked_person_location_role VALUES ('${p}', '${l}', '${r}');`).join("\n"));
const roster = (...people) => ({ version: 1, people });

test("ACCEPTANCE: the roster {nate} regenerates today's file and allow-list byte for byte, so nothing live changes", () => {
  const { yaml, allowlist: json } = generate();
  assert.equal(yaml, lf(fs.readFileSync(path.join(haDir, "cabin_security_presence.yaml"), "utf8")));
  assert.equal(json, lf(fs.readFileSync(path.join(haDir, "presence_people_allowlist.json"), "utf8")));
});

test("the checked-in roster is only people who are PRIMARY at that location in the seed SQL (drift check)", () => {
  const roster_ = JSON.parse(lf(fs.readFileSync(path.join(here, "presence-people.json"), "utf8")));
  const seed = parseSeed(fs.readFileSync(seedPath, "utf8"));
  for (const p of roster_.people) assert.ok(seed.get(`${p.personId}@${p.location}`)?.has("PRIMARY"), `${p.personId} is not PRIMARY in the seed`);
});

test("the roster holds opaque ids and entity ids only: no MAC, phone number, or anything address-like", () => {
  const text = fs.readFileSync(path.join(here, "presence-people.json"), "utf8");
  assert.doesNotMatch(text, /([0-9a-f]{2}[:-]){5}[0-9a-f]{2}/i, "MAC address");
  assert.doesNotMatch(text, /\+?\d[\d ()-]{8,}\d/, "phone number");
  assert.doesNotMatch(text, /@/, "email");
});

test("a second primary person gets their own sensor and four automations, and the shared note-start automation stays single", () => {
  const out = render(roster(NATE, CHILD), loadTemplates());
  assert.equal((out.match(/binary_sensor\.nate_phone_on_wifi/g) || []).length > 0, true);
  assert.match(out, /name: Child 1 Phone On WiFi/);
  assert.match(out, /check_authorized_user_phone_wifi\.sh child_1/);
  assert.match(out, /topic: cabin\/presence\/child_1\n/);
  assert.match(out, /topic: cabin\/presence\/child_1\/last_seen\n/);
  assert.match(out, /entity_id: device_tracker\.child_1_phone/);
  assert.match(out, /sensor\.child_1_phone_battery_level/);
  const ids = [...out.matchAll(/^  - id: (\S+)$/gm)].map((m) => m[1]);
  assert.equal(new Set(ids).size, ids.length, "automation ids are unique");
  assert.equal(ids.length, 4 * 2 + 1, "four per person plus the one shared note-start automation");
  assert.equal(ids.filter((i) => i === "cabin_presence_note_ha_start").length, 1);
  // Home Assistant derives an automation's entity id from its ALIAS, so two people must never share one either.
  const aliases = [...out.matchAll(/^    alias: (.+)$/gm)].map((m) => m[1]);
  assert.equal(new Set(aliases).size, aliases.length, "aliases are unique");
  assert.equal((out.match(/^command_line:\n/gm) || []).length, 1);
  assert.equal((out.match(/^automation:\n/gm) || []).length, 1);
  assert.doesNotMatch(out, /\{\{[A-Z]+\}\}/, "no unfilled placeholder");
});

test("the generated allow-list names people and locations only, never a topic", () => {
  const json = JSON.parse(allowlist(roster(NATE, CHILD)));
  assert.deepEqual(json, { version: 1, people: [{ personId: "nate", location: "cabin" }, { personId: "child_1", location: "cabin" }] });
  assert.doesNotMatch(JSON.stringify(json), /\//, "no topic strings in the file");
});

test("a person who is maintenance-only or not in the seed is REFUSED (D24 R-LR-4)", () => {
  assert.throws(() => validateRoster(roster(CHILD), seedWith(["child_1", "cabin", "MAINTENANCE"])), /not PRIMARY/);
  assert.throws(() => validateRoster(roster(CHILD), seedWith(["nate", "cabin", "PRIMARY"])), /not PRIMARY/);
  assert.throws(() => validateRoster(roster(CHILD), seedWith(["child_1", "home", "PRIMARY"])), /not PRIMARY/, "primary at another location is not enough");
  assert.doesNotThrow(() => validateRoster(roster(CHILD), seedWith(["child_1", "cabin", "PRIMARY"])));
  assert.doesNotThrow(() => validateRoster(roster(CHILD), seedWith(["child_1", "cabin", "MAINTENANCE"], ["child_1", "cabin", "PRIMARY"])), "both roles: primary wins");
});

test("a commented-out seed row does not count", () => {
  const seed = parseSeed("-- ('child_1', 'cabin', 'PRIMARY')\nINSERT INTO tracked_person_location_role VALUES ('nate', 'cabin', 'PRIMARY');");
  assert.throws(() => validateRoster(roster(CHILD), seed), /not PRIMARY/);
});

test("bad ids, locations, entity ids, unknown keys, duplicates and oversized rosters are refused", () => {
  const seed = seedWith(["child_1", "cabin", "PRIMARY"], ["Child-1", "cabin", "PRIMARY"], ["x", "garage", "PRIMARY"]);
  const bad = [
    { ...CHILD, personId: "Child-1" }, { ...CHILD, personId: "a".repeat(33) }, { ...CHILD, personId: "" }, { ...CHILD, personId: "../x" },
    { ...CHILD, location: "garage" }, { ...CHILD, zone: "cabin" }, { ...CHILD, gpsTracker: "sensor.x" }, { ...CHILD, batterySensor: "device_tracker.x" },
    { ...CHILD, mac: "aa:bb:cc:dd:ee:ff" }, { ...CHILD, topic: "cabin/security/armed_away" }, { personId: "child_1", location: "cabin" },
  ];
  for (const p of bad) assert.throws(() => validateRoster(roster(p), seed), /roster refused/, JSON.stringify(p));
  assert.throws(() => validateRoster(roster(CHILD, CHILD), seed), /listed twice/);
  assert.throws(() => validateRoster({ version: 2, people: [] }, seed), /version: 1/);
  assert.throws(() => validateRoster({ version: 1, people: [], extra: 1 }, seed), /unknown roster keys/);
  const many = Array.from({ length: 33 }, (_, i) => ({ ...CHILD, personId: `p${i}` }));
  assert.throws(() => validateRoster(roster(...many), seedWith(...many.map((m) => [m.personId, "cabin", "PRIMARY"]))), /more than 32/);
});

const T = "tracked_person_location_role";
const primary = (seed, p, l = "cabin") => seed.get(`${p}@${l}`)?.has("PRIMARY") === true;

test("parseSeed: line, inline and block comments never produce a person", () => {
  const sql = `-- ('c1', 'cabin', 'PRIMARY')
    INSERT INTO ${T} (person_id, location, role) VALUES
      ('nate', 'cabin', 'PRIMARY'), -- ('c2', 'cabin', 'PRIMARY'),
      /* ('c3', 'cabin', 'PRIMARY'), */ ('c4', 'cabin', 'MAINTENANCE')
    ON CONFLICT DO NOTHING;
    /* INSERT INTO ${T} VALUES ('c5', 'cabin', 'PRIMARY');
       spanning lines */`;
  const seed = parseSeed(sql);
  assert.ok(primary(seed, "nate"));
  for (const id of ["c1", "c2", "c3", "c5"]) assert.equal(seed.has(`${id}@cabin`), false, id);
  assert.ok(seed.get("c4@cabin").has("MAINTENANCE") && !primary(seed, "c4"));
});

test("parseSeed: a comment marker inside a string literal is not a comment", () => {
  assert.equal(stripSqlComments("SELECT '-- not a comment', '/* nor this */'"), "SELECT '-- not a comment', '/* nor this */'");
  const seed = parseSeed(`INSERT INTO ${T} VALUES ('a--b', 'cabin', 'PRIMARY');`);
  assert.ok(primary(seed, "a--b"));
});

test("parseSeed: only INSERT INTO the tracked-person table counts", () => {
  const seed = parseSeed(`INSERT INTO other_table VALUES ('c1', 'cabin', 'PRIMARY');
    INSERT INTO ${T}_archive VALUES ('c2', 'cabin', 'PRIMARY');
    UPDATE ${T} SET role = 'PRIMARY' WHERE person_id = 'c3';
    SELECT ('c4', 'cabin', 'PRIMARY');
    INSERT INTO ${T} VALUES ('nate', 'cabin', 'PRIMARY');`);
  assert.deepEqual([...seed.keys()], ["nate@cabin"]);
});

test("parseSeed: a later DELETE removes the person, narrowed by location and role when given", () => {
  const base = `INSERT INTO ${T} VALUES ('c1', 'cabin', 'PRIMARY'), ('c1', 'home', 'PRIMARY'), ('c2', 'cabin', 'PRIMARY'), ('c2', 'cabin', 'MAINTENANCE');`;
  assert.equal(primary(parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1';`), "c1"), false);
  assert.equal(primary(parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1';`), "c1", "home"), false);
  const byLocation = parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1' AND location = 'cabin';`);
  assert.equal(primary(byLocation, "c1"), false);
  assert.equal(primary(byLocation, "c1", "home"), true, "the other location stays");
  const byRole = parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c2' AND role = 'PRIMARY';`);
  assert.equal(primary(byRole, "c2"), false);
  assert.ok(byRole.get("c2@cabin").has("MAINTENANCE"), "the maintenance row stays");
  assert.equal(primary(parseSeed(base + `DELETE FROM ${T};`), "c1"), false, "no WHERE empties the table");
  assert.equal(primary(parseSeed(`DELETE FROM ${T} WHERE person_id = 'c1'; INSERT INTO ${T} VALUES ('c1', 'cabin', 'PRIMARY');`), "c1"), true, "a DELETE before the INSERT does not remove it");
  assert.equal(primary(parseSeed(`-- DELETE FROM ${T} WHERE person_id = 'c1';\n` + base), "c1"), true, "a commented-out DELETE does nothing");
});

test("a roster person the seed later deletes is refused", () => {
  const seed = parseSeed(`INSERT INTO ${T} VALUES ('child_1', 'cabin', 'PRIMARY'); DELETE FROM ${T} WHERE person_id = 'child_1';`);
  assert.throws(() => validateRoster(roster(CHILD), seed), /not PRIMARY/);
});

test("ids must be single-underscore words, so two ids can never share a sensor entity id", () => {
  const ids = ["a__b", "_a", "a_", "1a", "a-b", "A", "a b", "_", "a".repeat(33)];
  for (const personId of ids) {
    assert.throws(() => validateRoster(roster({ ...CHILD, personId }), seedWith([personId, "cabin", "PRIMARY"])), /roster refused/, personId);
  }
  for (const personId of ["nate", "child_1", "a1_b2", "a".repeat(32)]) {
    assert.doesNotThrow(() => validateRoster(roster({ ...CHILD, personId, gpsTracker: `device_tracker.${personId}`, batterySensor: `sensor.${personId}` }), seedWith([personId, "cabin", "PRIMARY"])), personId);
  }
  // and every accepted id slugs back to itself from its display name
  for (const personId of ["nate", "child_1", "a1_b2"]) {
    assert.equal(displayName(personId).toLowerCase().replace(/ /g, "_"), personId);
  }
});

test("two people cannot share a GPS tracker or a battery sensor", () => {
  const other = { ...CHILD, personId: "child_2" };
  const seed = seedWith(["child_1", "cabin", "PRIMARY"], ["child_2", "cabin", "PRIMARY"]);
  assert.throws(() => validateRoster(roster(CHILD, { ...other, gpsTracker: CHILD.gpsTracker }), seed), /gpsTracker .* already used/);
  assert.throws(() => validateRoster(roster(CHILD, { ...other, batterySensor: CHILD.batterySensor }), seed), /batterySensor .* already used/);
  assert.doesNotThrow(() => validateRoster(roster(CHILD, { ...other, gpsTracker: "device_tracker.child_2_phone", batterySensor: "sensor.child_2_phone_battery_level" }), seed));
});

test("display names turn ids into the Home Assistant entity names", () => {
  assert.equal(displayName("nate"), "Nate");
  assert.equal(displayName("child_1"), "Child 1");
});
