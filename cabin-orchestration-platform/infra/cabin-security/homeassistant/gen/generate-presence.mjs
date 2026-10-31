// W-38 slice 3 (D24): generate the per-person Home Assistant presence YAML and the publish allow-list entries from a roster.
//
//   node generate-presence.mjs            write ../cabin_security_presence.yaml and ../presence_people_allowlist.json
//   node generate-presence.mjs --check    exit 1 if either file differs from what the roster would generate
//
// The roster (presence-people.json) lists PRIMARY-role people only, with opaque ids and Home Assistant entity ids that are already
// public for the existing nate block. It holds no MAC address, phone number or real name; the phone's MAC and LAN stay in the
// uncommitted authorized_user_phones.conf. The generator REFUSES a person who is not PRIMARY at that location in
// scripts/host/w38-register-primary-presence.sql, so a maintenance-only person can never get a check or a publish permission
// (D24 R-LR-4). It cannot see the live backend registry: a person assigned in Config is not generated until the roster is
// updated. Nothing here is applied to Home Assistant; that stays a manual step.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const haDir = path.resolve(here, "..");
const repoRoot = path.resolve(here, "../../../../..");

// Lowercase words joined by single underscores, so two ids can never slug to the same Home Assistant entity id
// (a__b and a_b would both become binary_sensor.a_b_phone_on_wifi). 1 to 32 characters.
export const PERSON_ID = /^(?=.{1,32}$)[a-z][a-z0-9]*(_[a-z0-9]+)*$/;
export const LOCATIONS = ["cabin", "home"];
const ZONE = /^zone\.[a-z0-9_]+$/;
const TRACKER = /^device_tracker\.[a-z0-9_]+$/;
const BATTERY = /^sensor\.[a-z0-9_]+$/;
const ROSTER_KEYS = ["personId", "location", "zone", "gpsTracker", "batterySensor"];

const lf = (text) => text.replace(/\r\n/g, "\n");

const SEED_TABLE = "tracked_person_location_role";

/** Remove -- and block comments that are outside string literals, so a commented-out row can never be read as a real one. */
export function stripSqlComments(sql) {
  let out = "";
  let i = 0;
  let inString = false;
  while (i < sql.length) {
    const c = sql[i];
    if (inString) {
      out += c;
      if (c === "'") {
        if (sql[i + 1] === "'") { out += "'"; i += 2; continue; } // '' is an escaped quote
        inString = false;
      }
      i += 1;
    } else if (c === "'") {
      inString = true; out += c; i += 1;
    } else if (c === "-" && sql[i + 1] === "-") {
      while (i < sql.length && sql[i] !== "\n") i += 1;
    } else if (c === "/" && sql[i + 1] === "*") {
      const end = sql.indexOf("*/", i + 2);
      const body = end < 0 ? sql.slice(i + 2) : sql.slice(i + 2, end);
      // PostgreSQL nests block comments, this reader does not: a nested opener would make it read a row the database ignores.
      if (end < 0 || body.includes("/*")) throw new Error("seed refused: unterminated or nested block comment");
      i = end + 2;
      out += " ";
    } else {
      out += c; i += 1;
    }
  }
  return out;
}

/** Split on ; outside string literals. */
function statements(sql) {
  const out = [];
  let cur = "";
  let inString = false;
  for (let i = 0; i < sql.length; i += 1) {
    const c = sql[i];
    if (c === "'") inString = !inString; // '' toggles twice, which is correct
    if (c === ";" && !inString) { out.push(cur); cur = ""; } else cur += c;
  }
  if (inString) throw new Error("seed refused: unterminated string literal");
  if (cur.trim()) out.push(cur);
  return out.map((t) => t.trim()).filter(Boolean);
}

const ROW = "\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*'(PRIMARY|MAINTENANCE)'\\s*\\)";
const INSERT_FORM = new RegExp(
  `^INSERT\\s+INTO\\s+${SEED_TABLE}\\s*\\(\\s*person_id\\s*,\\s*location\\s*,\\s*role\\s*\\)\\s*VALUES\\s*((?:${ROW}\\s*,\\s*)*${ROW})(?:\\s+ON\\s+CONFLICT\\s+DO\\s+NOTHING)?$`, "i");
const DELETE_FORM = new RegExp(`^DELETE\\s+FROM\\s+${SEED_TABLE}\\s+WHERE\\s+(.+)$`, "i");
const CONDITION = /^(person_id|location|role)\s*=\s*'([^']+)'$/i;

/**
 * Map of "person@location" to the set of roles the seed SQL leaves in the table.
 *
 * A STRICT allow-list, not a reader that skips what it does not understand: the only statements accepted are
 *   INSERT INTO tracked_person_location_role (person_id, location, role) VALUES ('p', 'l', 'PRIMARY'|'MAINTENANCE'), ...
 *       [ON CONFLICT DO NOTHING]
 *   DELETE FROM tracked_person_location_role WHERE person_id = '..' [AND location = '..'] [AND role = '..']
 * and anything else (UPDATE, TRUNCATE, DROP, a DELETE with OR or an unknown column or no WHERE, a schema-qualified or quoted
 * table name, a CTE, a nested block comment, any other statement at all) makes it throw, so the generator refuses to run
 * against a seed whose effect it cannot read exactly. Comments outside string literals are ignored.
 */
/** Throw on any non-ASCII character outside a quoted value: a look-alike letter in a keyword or name would be read differently by a human and by the database. */
function assertAsciiOutsideStrings(sql) {
  let inString = false;
  for (const ch of sql) {
    if (ch === "'") inString = !inString;
    else if (!inString && ch.codePointAt(0) > 127) throw new Error(`seed refused: non-ASCII character ${JSON.stringify(ch)} outside a quoted value`);
  }
}

export function parseSeed(sqlText) {
  const seed = new Map();
  const present = new Set(); // person@location@role rows currently in the table, to catch a duplicate INSERT
  const add = (p, l, r) => { const k = `${p}@${l}`; if (!seed.has(k)) seed.set(k, new Set()); seed.get(k).add(r); present.add(`${k}@${r}`); };
  const code = stripSqlComments(lf(sqlText));
  assertAsciiOutsideStrings(code);
  for (const stmt of statements(code)) {
    const oneLine = stmt.replace(/\s+/g, " ");
    const insert = oneLine.match(INSERT_FORM);
    if (insert) {
      const tolerant = /\bON\s+CONFLICT\s+DO\s+NOTHING$/i.test(oneLine);
      for (const m of insert[1].matchAll(new RegExp(ROW, "gi"))) {
        // The keywords are case-insensitive but the role is the exact stored value: 'primary' is not 'PRIMARY'.
        if (m[3] !== m[3].toUpperCase()) throw new Error(`seed refused: role must be exactly PRIMARY or MAINTENANCE, got ${JSON.stringify(m[3])}`);
        // A row that is already there makes a plain INSERT fail on the primary key, so the seed would never apply while this
        // reader believed it had: refuse it unless the statement says ON CONFLICT DO NOTHING.
        if (present.has(`${m[1]}@${m[2]}@${m[3]}`) && !tolerant) {
          throw new Error(`seed refused: duplicate row ${JSON.stringify([m[1], m[2], m[3]])} without ON CONFLICT DO NOTHING`);
        }
        add(m[1], m[2], m[3]);
      }
      continue;
    }
    const del = oneLine.match(DELETE_FORM);
    if (del) {
      const conds = {};
      for (const part of del[1].split(/\s+AND\s+/i)) {
        const c = part.trim().match(CONDITION);
        if (!c || conds[c[1].toLowerCase()] !== undefined) throw new Error(`seed refused: unsupported DELETE condition ${JSON.stringify(part)}`);
        conds[c[1].toLowerCase()] = c[2];
      }
      for (const [key, roles] of [...seed]) {
        const [kp, kl] = key.split("@");
        if (conds.person_id !== undefined && kp !== conds.person_id) continue;
        if (conds.location !== undefined && kl !== conds.location) continue;
        if (conds.role !== undefined) {
          roles.delete(conds.role); present.delete(`${key}@${conds.role}`);
          if (roles.size === 0) seed.delete(key);
        } else {
          for (const role of roles) present.delete(`${key}@${role}`);
          seed.delete(key);
        }
      }
      continue;
    }
    throw new Error(`seed refused: statement not in the supported forms: ${JSON.stringify(oneLine.slice(0, 80))}`);
  }
  return seed;
}

export function validateRoster(roster, seed) {
  const errors = [];
  if (!roster || roster.version !== 1 || !Array.isArray(roster.people)) {
    throw new Error("roster must be {version: 1, people: [...]}");
  }
  const extra = Object.keys(roster).filter((k) => !["version", "people"].includes(k));
  if (extra.length) errors.push(`unknown roster keys: ${extra.join(", ")}`);
  if (roster.people.length > 32) errors.push("more than 32 people");
  const seen = new Set();
  const trackers = new Set();
  const batteries = new Set();
  for (const p of roster.people) {
    const who = JSON.stringify(p?.personId);
    const keys = Object.keys(p || {});
    const unknown = keys.filter((k) => !ROSTER_KEYS.includes(k));
    if (unknown.length) errors.push(`${who}: unknown keys ${unknown.join(", ")}`);
    for (const k of ROSTER_KEYS) if (typeof p?.[k] !== "string") errors.push(`${who}: ${k} is required and must be a string`);
    if (typeof p?.personId !== "string") continue;
    if (!PERSON_ID.test(p.personId)) errors.push(`${who}: personId must match ${PERSON_ID}`);
    if (!LOCATIONS.includes(p.location)) errors.push(`${who}: location must be one of ${LOCATIONS.join(", ")}`);
    if (!ZONE.test(p.zone || "")) errors.push(`${who}: zone must look like zone.cabin`);
    if (!TRACKER.test(p.gpsTracker || "")) errors.push(`${who}: gpsTracker must look like device_tracker.xxx`);
    if (!BATTERY.test(p.batterySensor || "")) errors.push(`${who}: batterySensor must look like sensor.xxx`);
    if (seen.has(p.personId)) errors.push(`${who}: listed twice (one location per person in v1)`);
    seen.add(p.personId);
    if (trackers.has(p.gpsTracker)) errors.push(`${who}: gpsTracker ${p.gpsTracker} is already used by another person`);
    if (batteries.has(p.batterySensor)) errors.push(`${who}: batterySensor ${p.batterySensor} is already used by another person`);
    trackers.add(p.gpsTracker);
    batteries.add(p.batterySensor);
    const roles = seed.get(`${p.personId}@${p.location}`);
    if (!roles || !roles.has("PRIMARY")) {
      errors.push(`${who}: not PRIMARY at ${p.location} in scripts/host/w38-register-primary-presence.sql; a maintenance-only or unregistered person never gets a check or a publish permission`);
    }
  }
  if (errors.length) throw new Error(`roster refused:\n  ${errors.join("\n  ")}`);
}

/** "child_1" becomes "Child 1": the display name Home Assistant turns into binary_sensor.child_1_phone_on_wifi. */
export function displayName(personId) {
  return personId.split("_").filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(" ");
}

// The startup automation's id and alias never had a person in them. Nate's live one keeps them exactly (renaming a live
// automation id would orphan its history); every other person gets a person-specific one so two people never share an id.
function startupIds(p) {
  if (p.personId === "nate") {
    return { id: "cabin_security_publish_presence_at_startup", alias: "Cabin Security - Publish Phone-Based Presence At Startup" };
  }
  return {
    id: `cabin_security_publish_${p.personId}_presence_at_startup`,
    alias: `Cabin Security - Publish ${displayName(p.personId)} Phone-Based Presence At Startup`,
  };
}

function fill(template, p) {
  return template
    .replaceAll("{{STARTUP_ID}}", startupIds(p).id)
    .replaceAll("{{STARTUP_ALIAS}}", startupIds(p).alias)
    .replaceAll("{{LASTSEEN_ALIAS}}", p.personId === "nate"
      ? "Cabin Presence - Publish Phone Last-Seen Heartbeat"
      : `Cabin Presence - Publish ${displayName(p.personId)} Phone Last-Seen Heartbeat`)
    .replaceAll("{{PERSON}}", p.personId)
    .replaceAll("{{NAME}}", displayName(p.personId))
    .replaceAll("{{LOCATION}}", p.location)
    .replaceAll("{{ZONE}}", p.zone)
    .replaceAll("{{TRACKER}}", p.gpsTracker)
    .replaceAll("{{BATTERY}}", p.batterySensor);
}

export function render(roster, templates) {
  const people = roster.people;
  return lf(templates.header)
    + people.map((p) => fill(lf(templates.sensor), p)).join("")
    + lf(templates.mid)
    + people.map((p) => fill(lf(templates.person_a), p)).join("")
    + lf(templates.shared)
    + people.map((p) => fill(lf(templates.person_b), p)).join("\n");
}

/** The entries the Python publisher adds: ids and locations only. It builds the exact topics itself; JSON cannot name a topic. */
export function allowlist(roster) {
  return JSON.stringify({ version: 1, people: roster.people.map((p) => ({ personId: p.personId, location: p.location })) }, null, 2) + "\n";
}

export function loadTemplates(dir = path.join(here, "templates")) {
  const out = {};
  for (const name of ["header", "sensor", "mid", "person_a", "shared", "person_b"]) {
    out[name] = fs.readFileSync(path.join(dir, `${name}.yaml`), "utf8");
  }
  return out;
}

export function generate({ rosterPath = path.join(here, "presence-people.json"),
                           seedPath = path.join(repoRoot, "scripts/host/w38-register-primary-presence.sql") } = {}) {
  const roster = JSON.parse(lf(fs.readFileSync(rosterPath, "utf8")));
  validateRoster(roster, parseSeed(fs.readFileSync(seedPath, "utf8")));
  return { yaml: render(roster, loadTemplates()), allowlist: allowlist(roster) };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outYaml = path.join(haDir, "cabin_security_presence.yaml");
  const outJson = path.join(haDir, "presence_people_allowlist.json");
  const { yaml, allowlist: json } = generate();
  if (process.argv.includes("--check")) {
    const same = lf(fs.readFileSync(outYaml, "utf8")) === yaml && lf(fs.readFileSync(outJson, "utf8")) === json;
    if (!same) {
      console.error("generated presence files differ from the roster: run node generate-presence.mjs and review the diff");
      process.exit(1);
    }
    console.log("presence files match the roster");
  } else {
    fs.writeFileSync(outYaml, yaml);
    fs.writeFileSync(outJson, json);
    console.log(`wrote ${path.relative(repoRoot, outYaml)} and ${path.relative(repoRoot, outJson)}`);
  }
}
