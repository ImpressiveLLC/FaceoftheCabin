// Composition only: enrollment, source records, history and command targets stay unchanged.
const electricalFields = ['power', 'energy', 'voltage', 'current'];
const active = d => d.attributes?.enabled !== false && !d.attributes?.candidate &&
  !['CANDIDATE', 'IGNORED', 'DEFERRED', 'REMOVED'].includes(d.attributes?.deviceLifecycle);
export function electricalMonitoringDevices(devices) {
  const hidden = new Set();
  const cards = new Map();
  for (const parent of devices) {
    if (!active(parent) || parent.attributes?.parentDeviceId ||
        !(parent.type === 'POWER_METER' || parent.attributes?.smartSwitch === true)) continue;
    const children = devices.filter(d => d.attributes?.parentDeviceId === parent.deviceId &&
      d.location === parent.location && active(d));
    const attrs = {...parent.attributes};
    for (const child of children) {
      const fields = child.attributes?.sourceReportedFields;
      if (!child.attributes?.entityId?.startsWith('sensor.') || !Array.isArray(fields) || fields.length !== 1) continue;
      const field = fields[0];
      if (!electricalFields.includes(field) || child.attributes?.serviceRole === 'configuration') continue;
      // A native channel remains authoritative even when its current value is missing.
      if (!attrs.vendorReportedFields?.includes(field) && attrs[field] == null &&
          children.filter(d => d.attributes?.sourceReportedFields?.includes(field)).length === 1) {
        attrs[field] = child.attributes[field];
      }
      hidden.add(child.deviceId);
    }
    const states = children.filter(d => d.attributes?.entityId?.startsWith('switch.'))
      .map(d => d.attributes?.state).filter(s => s === 'ON' || s === 'OFF');
    if (states.length && new Set(states).size === 1) attrs.mappedSwitchState = states[0];
    cards.set(parent.deviceId, {...parent, attributes:attrs});
  }
  return devices.filter(d => !hidden.has(d.deviceId)).map(d => cards.get(d.deviceId) || d);
}

export function electricalValue(attributes = {}) {
  const finite = value => typeof value === 'number' && Number.isFinite(value);
  const metrics = [['power','W'], ['energy','kWh'], ['voltage','V'], ['current','A']]
    .filter(([field]) => finite(attributes[field])).map(([field,unit]) => `${attributes[field]} ${unit}`);
  if (!finite(attributes.power) && !attributes.sourceReportedFields?.length) metrics.unshift('Power unavailable');
  if (!finite(attributes.energy) && !attributes.sourceReportedFields?.length) metrics.push('Energy unavailable');
  if (attributes.mappedSwitchState) metrics.push(attributes.mappedSwitchState);
  return metrics.join(' · ') || 'Measurement unavailable';
}
