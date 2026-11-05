// Device Manager search: one box that reduces the list by ANY data element of a device or service, in the
// style people know from Elasticsearch / Kibana / Gmail:
//
//   kitchen                  every word must match somewhere (implicit AND), case-insensitive, substring
//   "mech room"              quoted phrase
//   room:kitchen             restrict to one field; the field is the last part of its dotted path
//   state:offline type:sensor  fields combine with AND
//   battery:<20  rssi:>=-70  numeric comparison (<, <=, >, >=, =)
//   -zigbee   !zigbee        exclude
//   leak OR flood            alternatives (also "|")
//   z2m-*  *_temp            * wildcard
//   parent:kidde  workflow:alerting  kind:service  lifecycle:ignored   derived fields
//
// Separators (_ - . : /) count as spaces, so "mech room" finds "temp_mech_room" and "z2m" finds "z2m-plug-1".
// Everything is matched against the whole device record (id, name, type, state, location, every attribute at
// any depth), so a new attribute becomes searchable without touching this file.

const SEP = /[_\-./:\\]+/g;

export function normalize(value) {
  return String(value).toLowerCase().replace(SEP, " ").replace(/\s+/g, " ").trim();
}

// Field names people type -> the path suffix(es) they mean.
const FIELD_ALIASES = {
  id: ["deviceid"],
  device: ["name", "deviceid"],
  loc: ["location"],
  status: ["state"],
  category: ["type"],
};

// ── Query parsing ──────────────────────────────────────────────────────────

function tokenize(query) {
  const tokens = [];
  const re = /(-|!)?(?:([A-Za-z_][\w.]*):)?(?:"([^"]*)"|(\S+))/g;
  let m;
  while ((m = re.exec(query)) !== null) {
    const [, neg, field, quoted, bare] = m;
    if (quoted == null && bare === "|") { tokens.push({ or: true }); continue; }
    if (quoted == null && bare === "OR" && !field && !neg) { tokens.push({ or: true }); continue; }
    let value = quoted != null ? quoted : bare;
    let op = ":";
    if (quoted == null && field) {
      const cmp = /^(>=|<=|>|<|=)(-?\d+(?:\.\d+)?)$/.exec(value);
      if (cmp) { op = cmp[1]; value = cmp[2]; }
    }
    tokens.push({ neg: !!neg, field: field ? field.toLowerCase() : null, op, value, phrase: quoted != null });
  }
  return tokens;
}

/** Parses a query into OR-groups of AND-terms. `empty` is true when nothing searchable was typed. */
export function parseDeviceQuery(query) {
  const clauses = [[]];
  for (const token of tokenize(String(query || ""))) {
    if (token.or) { if (clauses[clauses.length - 1].length) clauses.push([]); continue; }
    const text = normalize(token.value);
    if (!text && token.op === ":") continue;
    clauses[clauses.length - 1].push({ ...token, text, wildcard: token.value.includes("*") });
  }
  const nonEmpty = clauses.filter(c => c.length > 0);
  return { clauses: nonEmpty, empty: nonEmpty.length === 0 };
}

// ── Indexing ───────────────────────────────────────────────────────────────

function flatten(value, path, out, depth = 0) {
  if (value == null || depth > 6) return;
  if (Array.isArray(value)) {
    value.forEach(item => (typeof item === "object" && item !== null) ? flatten(item, path, out, depth + 1)
      : flatten(item, path, out, depth + 1));
    return;
  }
  if (typeof value === "object") {
    Object.entries(value).forEach(([k, v]) => flatten(v, path ? `${path}.${k}` : k, out, depth + 1));
    return;
  }
  const raw = String(value);
  out.push({
    path: path.toLowerCase(),
    last: (path.split(".").pop() || "").toLowerCase(),
    raw,
    text: normalize(raw),
    number: typeof value === "number" ? value : (typeof value === "string" && /^-?\d+(\.\d+)?$/.test(value.trim()) ? Number(value) : null),
    // Also index the un-normalized key name so searching for an attribute NAME (e.g. "linkquality") finds it.
  });
  if (path) out.push({ path: "_key", last: "_key", raw: path, text: normalize(path), number: null });
}

/**
 * One searchable record per device: every field of the device and its attributes, plus derived fields people
 * look for (kind, lifecycle, parent, workflows). `ctx` supplies what a single device can't know.
 */
export function buildDeviceIndex(devices, { workflows = [], kindOf = null, lifecycleOf = null } = {}) {
  const byId = new Map(devices.map(d => [d.deviceId, d]));
  const index = new Map();
  for (const d of devices) {
    const entries = [];
    const { attributes, ...rest } = d;
    flatten(rest, "", entries);
    flatten(attributes, "attributes", entries);
    if (kindOf) flatten(kindOf(d), "kind", entries);
    if (lifecycleOf) flatten(lifecycleOf(d), "lifecycle", entries);
    const parentId = attributes?.parentDeviceId;
    if (parentId) {
      flatten(parentId, "parent", entries);
      flatten(byId.get(parentId)?.name, "parent", entries);
    }
    // A parent also answers to its services' names, so searching a service name finds the device it hangs off.
    const children = devices.filter(c => c.attributes?.parentDeviceId === d.deviceId);
    children.forEach(c => { flatten(c.name, "service", entries); flatten(c.deviceId, "service", entries); });
    (workflows || []).filter(w => w.triggerDeviceId === d.deviceId
      || (w.actions || []).some(a => a.targetDeviceId === d.deviceId))
      .forEach(w => flatten(w.name, "workflow", entries));
    index.set(d.deviceId, entries);
  }
  return index;
}

// ── Matching ───────────────────────────────────────────────────────────────

function wildcardRegex(text) {
  const escaped = text.split("*").map(p => p.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*");
  return new RegExp(escaped, "i");
}

function fieldMatches(entry, field) {
  const wanted = FIELD_ALIASES[field] || [field];
  return wanted.some(w => entry.last === w || entry.path === w || entry.path.endsWith(`.${w}`));
}

function termMatches(term, entries) {
  const scope = term.field ? entries.filter(e => fieldMatches(e, term.field)) : entries;
  if (term.op !== ":") {
    const target = Number(term.value);
    return scope.some(e => e.number != null && (
      term.op === ">" ? e.number > target : term.op === ">=" ? e.number >= target
        : term.op === "<" ? e.number < target : term.op === "<=" ? e.number <= target : e.number === target));
  }
  if (term.wildcard) {
    const re = wildcardRegex(term.text);
    return scope.some(e => re.test(e.text) || re.test(e.raw.toLowerCase()));
  }
  // Without a field, each word of an unquoted term must be present; the words of a phrase must be adjacent.
  return scope.some(e => e.text.includes(term.text));
}

export function matchesDeviceQuery(parsed, entries) {
  if (parsed.empty) return true;
  return parsed.clauses.some(clause => clause.every(term => termMatches(term, entries || []) !== term.neg));
}

/** Convenience: the devices that satisfy `query`, in their original order. */
export function searchDevices(devices, query, ctx) {
  const parsed = parseDeviceQuery(query);
  if (parsed.empty) return devices;
  const index = buildDeviceIndex(devices, ctx);
  return devices.filter(d => matchesDeviceQuery(parsed, index.get(d.deviceId)));
}
