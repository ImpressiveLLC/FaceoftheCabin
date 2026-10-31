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
const seedWith = (...rows) => parseSeed(rows.map(([p, l, r]) => `INSERT INTO tracked_person_location_role (person_id, location, role) VALUES ('${p}', '${l}', '${r}');`).join("\n"));
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
  const seed = parseSeed("-- ('child_1', 'cabin', 'PRIMARY')\nINSERT INTO tracked_person_location_role (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY');");
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
    /* INSERT INTO ${T} (person_id, location, role) VALUES ('c5', 'cabin', 'PRIMARY');
       spanning lines */`;
  const seed = parseSeed(sql);
  assert.ok(primary(seed, "nate"));
  for (const id of ["c1", "c2", "c3", "c5"]) assert.equal(seed.has(`${id}@cabin`), false, id);
  assert.ok(seed.get("c4@cabin").has("MAINTENANCE") && !primary(seed, "c4"));
});

test("parseSeed: a comment marker inside a string literal is not a comment", () => {
  assert.equal(stripSqlComments("SELECT '-- not a comment', '/* nor this */'"), "SELECT '-- not a comment', '/* nor this */'");
  const seed = parseSeed(`INSERT INTO ${T} (person_id, location, role) VALUES ('a--b', 'cabin', 'PRIMARY');`);
  assert.ok(primary(seed, "a--b"));
});

const INS = `INSERT INTO ${T} (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY')`;
const refuses = (sql, why) => assert.throws(() => parseSeed(sql), /seed refused/, why);

test("the real seed file still parses to exactly nate@cabin PRIMARY", () => {
  const seed = parseSeed(fs.readFileSync(seedPath, "utf8"));
  assert.deepEqual([...seed.keys()], ["nate@cabin"]);
  assert.deepEqual([...seed.get("nate@cabin")], ["PRIMARY"]);
});

test("parseSeed is a strict allow-list: anything but the exact INSERT form and a simple AND-only DELETE makes it refuse", () => {
  // the demoting UPDATE: bob is seeded PRIMARY and then set to MAINTENANCE; a lenient reader would still generate him
  refuses(`INSERT INTO ${T} (person_id, location, role) VALUES ('bob', 'cabin', 'PRIMARY'); UPDATE ${T} SET role = 'MAINTENANCE' WHERE person_id = 'bob';`, "UPDATE");
  refuses(`${INS}; TRUNCATE ${T};`, "TRUNCATE");
  refuses(`${INS}; TRUNCATE TABLE ${T};`, "TRUNCATE TABLE");
  refuses(`${INS}; DROP TABLE ${T};`, "DROP");
  refuses(`${INS}; DELETE FROM ${T} WHERE person_id = 'nate' OR location = 'cabin';`, "DELETE with OR");
  refuses(`${INS}; DELETE FROM ${T} WHERE person_id = 'nate' AND created_at < now();`, "DELETE unknown column");
  refuses(`${INS}; DELETE FROM ${T} WHERE person_id IN ('nate');`, "DELETE IN");
  refuses(`${INS}; DELETE FROM ${T} WHERE person_id = 'nate' AND person_id = 'bob';`, "DELETE repeated column");
  refuses(`${INS}; DELETE FROM ${T};`, "DELETE with no WHERE");
  refuses(`INSERT INTO public.${T} (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY');`, "schema-qualified");
  refuses(`INSERT INTO "${T}" (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY');`, "quoted table");
  refuses(`WITH x AS (SELECT 1) INSERT INTO ${T} (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY');`, "CTE");
  refuses(`INSERT INTO ${T} VALUES ('nate', 'cabin', 'PRIMARY');`, "no column list");
  refuses(`INSERT INTO ${T} (role, person_id, location) VALUES ('PRIMARY', 'nate', 'cabin');`, "reordered columns");
  refuses(`${INS} ON CONFLICT (person_id) DO UPDATE SET role = 'MAINTENANCE';`, "ON CONFLICT DO UPDATE");
  refuses(`${INS} RETURNING *;`, "RETURNING");
  refuses(`INSERT INTO ${T} (person_id, location, role) VALUES ('nate', 'cabin', 'primary');`, "lowercase role");
  refuses(`INSERT INTO ${T} (person_id, location, role) SELECT 'nate', 'cabin', 'PRIMARY';`, "INSERT ... SELECT");
  refuses(`INSERT INTO other_table VALUES ('c1', 'cabin', 'PRIMARY');`, "any other statement");
  refuses(`SELECT 1; ${INS};`, "any other statement, even a harmless one");
  refuses(`DO $$ BEGIN UPDATE ${T} SET role = 'MAINTENANCE'; END $$;`, "DO block");
});

test("parseSeed refuses a nested block-comment opener, an unterminated comment and an unterminated string", () => {
  refuses(`/* outer /* inner */ ('c1', 'cabin', 'PRIMARY') */ ${INS};`, "nested block comment");
  refuses(`${INS}; /* never closed`, "unterminated comment");
  refuses(`${INS}; INSERT INTO ${T} (person_id, location, role) VALUES ('nate, 'cabin', 'PRIMARY');`, "unterminated string");
});

test("parseSeed refuses the nested-comment trick: a row that looks disabled but that PostgreSQL would run", () => {
  // PostgreSQL nests block comments, so after the inner */ the text up to the final */ is still a comment to the database,
  // but a reader that stops at the first */ would see an active INSERT for bob.
  refuses(`/* disabled /* note */ INSERT INTO ${T} (person_id, location, role) VALUES ('bob', 'cabin', 'PRIMARY'); -- */ ${INS};`, "nested comment");
});

test("parseSeed refuses non-ASCII outside quoted values, but allows it inside a value or a comment", () => {
  refuses(`INSERT INTO ${T} (person_id, location, r\u043ele) VALUES ('nate', 'cabin', 'PRIMARY');`, "Cyrillic o in a column name");
  refuses(`INSERT INTO ${T} (person_id, location, role) VALUES ('nate', 'cabin', 'PRIMARY')\u00a0ON CONFLICT DO NOTHING;`, "non-breaking space");
  refuses(`\u2060${INS};`, "zero-width joiner");
  assert.doesNotThrow(() => parseSeed(`-- r\u00e9sum\u00e9\n${INS};`), "non-ASCII in a comment is ignored");
  assert.ok(parseSeed(`INSERT INTO ${T} (person_id, location, role) VALUES ('ren\u00e9', 'cabin', 'PRIMARY');`).has("ren\u00e9@cabin"), "inside a quoted value");
});

test("parseSeed refuses a duplicate row unless the statement says ON CONFLICT DO NOTHING", () => {
  const row = `('nate', 'cabin', 'PRIMARY')`;
  refuses(`INSERT INTO ${T} (person_id, location, role) VALUES ${row}, ${row};`, "duplicate within one statement");
  refuses(`${INS}; ${INS};`, "duplicate across statements");
  assert.doesNotThrow(() => parseSeed(`${INS}; ${INS} ON CONFLICT DO NOTHING;`), "a tolerant re-insert is fine");
  assert.doesNotThrow(() => parseSeed(`${INS}; DELETE FROM ${T} WHERE person_id = 'nate'; ${INS};`), "re-insert after a delete is fine");
  assert.doesNotThrow(() => parseSeed(`${INS}; DELETE FROM ${T} WHERE person_id = 'nate' AND role = 'PRIMARY'; ${INS};`));
});

test("parseSeed accepts the supported forms, including several rows, ON CONFLICT DO NOTHING and extra whitespace", () => {
  const seed = parseSeed(`insert  into ${T}
      ( person_id , location , role )   values
      ('a', 'cabin', 'PRIMARY'),
      ('b', 'home', 'MAINTENANCE')
    on conflict do nothing;`);
  assert.deepEqual([...seed.keys()].sort(), ["a@cabin", "b@home"]);
});

test("parseSeed: a later DELETE removes the person, narrowed by location and role when given", () => {
  const base = `INSERT INTO ${T} (person_id, location, role) VALUES ('c1', 'cabin', 'PRIMARY'), ('c1', 'home', 'PRIMARY'), ('c2', 'cabin', 'PRIMARY'), ('c2', 'cabin', 'MAINTENANCE');`;
  assert.equal(primary(parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1';`), "c1"), false);
  assert.equal(primary(parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1';`), "c1", "home"), false);
  const byLocation = parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c1' AND location = 'cabin';`);
  assert.equal(primary(byLocation, "c1"), false);
  assert.equal(primary(byLocation, "c1", "home"), true, "the other location stays");
  const byRole = parseSeed(base + `DELETE FROM ${T} WHERE person_id = 'c2' AND role = 'PRIMARY';`);
  assert.equal(primary(byRole, "c2"), false);
  assert.ok(byRole.get("c2@cabin").has("MAINTENANCE"), "the maintenance row stays");
  assert.equal(primary(parseSeed(`DELETE FROM ${T} WHERE person_id = 'c1'; INSERT INTO ${T} (person_id, location, role) VALUES ('c1', 'cabin', 'PRIMARY');`), "c1"), true, "a DELETE before the INSERT does not remove it");
  assert.equal(primary(parseSeed(`-- DELETE FROM ${T} WHERE person_id = 'c1';\n` + base), "c1"), true, "a commented-out DELETE does nothing");
});

test("a roster person the seed later deletes is refused", () => {
  const seed = parseSeed(`INSERT INTO ${T} (person_id, location, role) VALUES ('child_1', 'cabin', 'PRIMARY'); DELETE FROM ${T} WHERE person_id = 'child_1';`);
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

test("the generator's own location check refuses a location outside cabin and home, even when the seed says PRIMARY there", () => {
  const seed = seedWith(["child_1", "garage", "PRIMARY"]);
  assert.throws(() => validateRoster(roster({ ...CHILD, location: "garage" }), seed), /location must be one of cabin, home/);
});

test("display names turn ids into the Home Assistant entity names", () => {
  assert.equal(displayName("nate"), "Nate");
  assert.equal(displayName("child_1"), "Child 1");
});
