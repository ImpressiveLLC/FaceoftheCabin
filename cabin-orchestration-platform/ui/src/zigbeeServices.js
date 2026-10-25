// D13 Amendment A1 (W-88): a native Zigbee device (z2m-*) is the parent of its Home Assistant sensor entities
// (ha-*). Monitoring shows one card per physical device, so an entity that repeats a reading its Zigbee parent
// already reports is not a second card. Found 2026-10-24: the outside temperature sensor showed two cards, the
// Zigbee device (blank after every backend restart until its next hourly report) and the HA entity that had been
// renamed "Temp Outside | Lower Level Temperature".
//
// Electrical readings (power, energy, voltage, current) are grouped by electricalMonitoring.js and are not
// touched here. A derived or template entity the parent does not report keeps its own card.
const COLLAPSIBLE = new Set(['temperature', 'humidity', 'battery', 'co2', 'pressure']);
const BY_TYPE = { TEMPERATURE_SENSOR: 'temperature', HUMIDITY_SENSOR: 'humidity', CO2_SENSOR: 'co2' };
const finite = value => typeof value === 'number' && Number.isFinite(value);

function measurementOf(device) {
  const deviceClass = String(device.attributes?.device_class ?? '').trim().toLowerCase();
  if (COLLAPSIBLE.has(deviceClass)) return deviceClass;
  const fromType = BY_TYPE[device.type];
  return COLLAPSIBLE.has(fromType) ? fromType : null;
}

export function collapseDuplicateZigbeeServices(devices) {
  const byId = new Map(devices.map(d => [d.deviceId, d]));
  const hidden = new Set();
  const filled = new Map();
  for (const child of devices) {
    const parentId = child.attributes?.parentDeviceId;
    if (!parentId || !child.deviceId.startsWith('ha-') || !child.attributes?.entityId?.startsWith('sensor.')) continue;
    const parent = byId.get(parentId);
    if (!parent || !parent.deviceId.startsWith('z2m-') || parent.location !== child.location ||
        parent.attributes?.enabled === false) continue;
    const measurement = measurementOf(child);
    if (!measurement) continue;
    const reported = parent.attributes?.vendorReportedFields || parent.attributes?.sourceReportedFields || [];
    if (!reported.includes(measurement)) continue;
    hidden.add(child.deviceId);
    // Right after a restart the parent has no reading until its next report; the entity's live value fills the
    // one card instead of leaving it blank.
    const current = filled.get(parentId) || parent;
    if (!finite(current.attributes?.[measurement]) && finite(child.attributes?.[measurement])) {
      filled.set(parentId, {
        ...current,
        attributes: { ...current.attributes, [measurement]: child.attributes[measurement] },
      });
    }
  }
  return devices.filter(d => !hidden.has(d.deviceId)).map(d => filled.get(d.deviceId) || d);
}
