// A read-only projection. Source records, enrollment, history and control targets are untouched.
const fields = ['power', 'energy', 'voltage', 'current'];
const active = d => d.attributes?.enabled !== false && !d.attributes?.candidate &&
  !['CANDIDATE', 'IGNORED', 'DEFERRED', 'REMOVED'].includes(d.attributes?.deviceLifecycle);
const eligible = d => !d.attributes?.candidate &&
  !['CANDIDATE', 'IGNORED', 'DEFERRED', 'REMOVED'].includes(d.attributes?.deviceLifecycle);
const finite = value => typeof value === 'number' && Number.isFinite(value);
const measurement = d => d.attributes?.entityId?.startsWith('sensor.') &&
  ['measurement', undefined].includes(d.attributes?.serviceRole) &&
  d.attributes?.sourceReportedFields?.length === 1 &&
  fields.includes(d.attributes.sourceReportedFields[0]);

export function electricalMonitoringDevices(devices) {
  const byId = new Map(devices.map(d => [d.deviceId, d]));
  const canonical = id => byId.get(id)?.attributes?.canonicalPhysicalId || id;
  const hidden = new Set(), cards = new Map();
  for (const parent of devices) {
    if (!eligible(parent) || parent.attributes?.parentDeviceId ||
        canonical(parent.deviceId) !== parent.deviceId ||
        !(parent.type === 'POWER_METER' || (parent.type === 'HOME_ASSISTANT_ENTITY' && parent.attributes?.smartSwitch === true))) continue;
    const children = devices.filter(d => d.attributes?.parentDeviceId &&
      canonical(d.attributes.parentDeviceId) === parent.deviceId &&
      d.location === parent.location && active(d));
    const readings = children.filter(measurement);
    // The same physical device can be listed under a second id (a Z2M rename registers the new
    // name as its own row that carries the live reading, the assigned row stays empty). Its
    // live values belong on the one card, never a second card or a blank one.
    const aliases = devices.filter(d => d.deviceId !== parent.deviceId && !d.attributes?.parentDeviceId &&
      canonical(d.deviceId) === parent.deviceId && d.location === parent.location &&
      !['IGNORED', 'REMOVED'].includes(d.attributes?.deviceLifecycle));
    if (!active(parent) && !readings.length) continue;
    const attrs = {...parent.attributes, monitoringGroup: true, measurementSources: {}};
    if (!active(parent)) fields.forEach(field => delete attrs[field]);
    for (const field of fields) {
      const matches = readings.filter(d => d.attributes.sourceReportedFields[0] === field);
      const native = active(parent) && (attrs.sourceReportedFields || attrs.vendorReportedFields || []).includes(field);
      const alias = matches.length === 1 ? null : aliases.find(d => finite(d.attributes?.[field]));
      if (native || (active(parent) && finite(attrs[field]))) {
        attrs.measurementSources[field] = parent.deviceId;
      } else if (matches.length === 1) {
        attrs[field] = matches[0].attributes[field];
        attrs.measurementSources[field] = matches[0].deviceId;
      } else if (alias) {
        attrs[field] = alias.attributes[field];
        attrs.measurementSources[field] = alias.deviceId;
      }
      // Ambiguous services remain visible separately; never silently choose one.
      if (native || matches.length === 1) matches.forEach(d => hidden.add(d.deviceId));
    }
    const unresolved = children.filter(d => d.type === 'POWER_METER' &&
      d.attributes?.serviceRole === 'unresolved');
    unresolved.forEach(d => hidden.add(d.deviceId));
    attrs.unresolvedServiceCount = unresolved.length;
    const switches = children.filter(d => d.attributes?.entityId?.startsWith('switch.'));
    const childStates = switches.map(d => d.attributes.state);
    // The device's own reported relay state, its alias's, then the mapped switch entities'.
    // Never inferred from watts or from ONLINE: ON at 0 W is a real state (nothing drawing).
    const ownState = ['ON','OFF'].includes(attrs.state) && active(parent) ? attrs.state
      : aliases.map(d => d.attributes?.state).find(v => ['ON','OFF'].includes(v));
    // The device's own report wins. Switch entities are only a fallback: HA also lists
    // configuration switches (metering-only mode) under the same parent, and one of those
    // saying OFF must not cancel the relay's own ON (breaker box, 2026-10-31).
    const states = ownState ? [ownState] : childStates;
    if (states.length && states.every(s => s === states[0] && ['ON','OFF'].includes(s))) {
      attrs.mappedSwitchState = states[0];
    }
    attrs.monitoringServices = children.map(d => ({deviceId:d.deviceId, role:d.attributes?.serviceRole || 'service'}));
    attrs.monitoringSourceNote = active(parent) ? 'Reported readings; acquisition time unverified' : 'Enabled services; parent disabled';
    cards.set(parent.deviceId, {...parent, type:'POWER_METER', attributes:attrs});
  }
  return devices.filter(d => !hidden.has(d.deviceId)).map(d => cards.get(d.deviceId) || d);
}

export function electricalValue(attributes = {}) {
  const single = attributes.sourceReportedFields?.length === 1 && !attributes.monitoringGroup;
  const quantities = single ? fields.filter(f => attributes.sourceReportedFields.includes(f)) : ['power','energy'];
  const units = {power:'W',energy:'kWh',voltage:'V',current:'A'};
  const metrics = quantities.map(field => finite(attributes[field])
    ? attributes[field] + ' ' + units[field]
    : (field === 'energy' ? 'Energy' : field === 'power' ? 'Power' : field === 'voltage' ? 'Voltage' : 'Current') + ' unavailable');
  // A smart-switch card always shows relay state next to power: unknown is said, never blank.
  if (attributes.mappedSwitchState) metrics.push(attributes.mappedSwitchState);
  else if (attributes.monitoringGroup) metrics.push('State unknown');
  return metrics.join(' · ') || 'Measurement unavailable';
}

// A card value like "55.8°F · 49.8%" or "Power unavailable · Energy unavailable · State unknown" is shown in two sizes:
// readings (anything with a digit) stay large, and the wording around them (unavailable, unknown, ON, OFF) drops to the
// card's smallest text size, so a sentence never outweighs a number. A single bare word (ONLINE, UNKNOWN, Dry) and any value with no wording part stay one plain, large string.
export function valueSegments(value) {
  const parts = typeof value === 'string' ? value.split(' · ') : [value];
  const compound = parts.length > 1;
  return parts.map(part => {
    const text = String(part);
    return { text, note: (/\s/.test(text.trim()) && /unavailable|unknown/i.test(text)) || (compound && !/\d/.test(text)) };
  });
}

// Follow only known same-location parent links. Cycles never send the user to an arbitrary device.
export function monitoringDeviceTarget(deviceId, devices = []) {
  const byId = new Map(devices.map(d => [d.deviceId, d]));
  let current = byId.get(deviceId);
  if (!current) return deviceId;
  const seen = new Set([deviceId]);
  while (current) {
    const alias = current.attributes?.canonicalPhysicalId;
    const nextId = alias && alias !== current.deviceId ? alias : current.attributes?.parentDeviceId;
    if (!nextId) return current.deviceId;
    const next = byId.get(nextId);
    if (!next || next.location !== current.location) return current.deviceId;
    if (seen.has(nextId)) return deviceId;
    seen.add(nextId);
    current = next;
  }
  return deviceId;
}
