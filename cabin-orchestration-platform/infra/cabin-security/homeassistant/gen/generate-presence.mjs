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
      i = end < 0 ? sql.length : end + 2;
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
  if (cur.trim()) out.push(cur);
  return out.map((t) => t.trim()).filter(Boolean);
}

/**
 * Map of "person@location" to the set of roles the seed SQL leaves in the table. Reads only INSERT INTO and DELETE FROM
 * tracked_person_location_role (other tables are ignored), ignores comments, and applies a later DELETE of a person (with its
 * optional location and role conditions) so a row that the file itself removes is not a primary person.
 */
export function parseSeed(sqlText) {
  const seed = new Map();
  const add = (p, l, r) => { const k = `${p}@${l}`; if (!seed.has(k)) seed.set(k, new Set()); seed.get(k).add(r); };
  for (const stmt of statements(stripSqlComments(lf(sqlText)))) {
    const insert = stmt.match(new RegExp(`^INSERT\\s+INTO\\s+${SEED_TABLE}\\b`, "i"));
    const del = stmt.match(new RegExp(`^DELETE\\s+FROM\\s+${SEED_TABLE}\\b`, "i"));
    if (insert) {
      const values = stmt.slice(insert[0].length).replace(/^[^]*?\bVALUES\b/i, "");
      for (const m of values.matchAll(/\(\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'(PRIMARY|MAINTENANCE)'\s*\)/g)) add(m[1], m[2], m[3]);
    } else if (del) {
      const where = stmt.slice(del[0].length).match(/^\s*WHERE\b([^]*)$/i);
      if (!where) { seed.clear(); continue; } // DELETE with no WHERE empties the table
      const cond = (name) => { const m = where[1].match(new RegExp(`\\b${name}\\s*=\\s*'([^']+)'`, "i")); return m ? m[1] : null; };
      const [p, l, r] = [cond("person_id"), cond("location"), cond("role")];
      for (const [key, roles] of [...seed]) {
        const [kp, kl] = key.split("@");
        if (p !== null && kp !== p) continue;
        if (l !== null && kl !== l) continue;
        if (r !== null) { roles.delete(r); if (roles.size === 0) seed.delete(key); } else seed.delete(key);
      }
    }
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
