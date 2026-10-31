// Device Manager's "Kind" facet: a physical device that runs on a battery, a physical device that is wired or
// mains powered, or a service (a subordinate Home Assistant entity or a standalone data element with no hardware
// of its own). Lets a battery maintenance list exclude the hundreds of services that have no battery at all.
export const DEVICE_KIND_OPTIONS = [
  { value: 'battery', label: 'Battery devices' },
  { value: 'wired', label: 'Wired / mains devices' },
  { value: 'service', label: 'Services / data' },
];

export function batteryLevel(device) {
  const battery = device.attributes?.battery;
  return typeof battery === 'number' && Number.isFinite(battery) ? battery : null;
}

// A root Home Assistant entity that no other device is filed under and that names no vendor or model is a data
// element (a bridge version, a backup state), not hardware. `devices` is the full list, so a root with children
// (a Kidde unit) still counts as the physical device it is.
export function makeKindOf(devices) {
  const parents = new Set(devices.map(d => d.attributes?.parentDeviceId).filter(Boolean));
  return device => {
    const a = device.attributes || {};
    if (a.parentDeviceId) return 'service';
    const isHaEntity = a.discoveredFrom === 'Home Assistant';
    if (isHaEntity && !a.vendor && !a.model && !parents.has(device.deviceId)) return 'service';
    if (String(a.powerSource ?? '').toLowerCase() === 'battery' || batteryLevel(device) !== null) return 'battery';
    return 'wired';
  };
}

export function deviceKind(device) {
  return makeKindOf([device])(device);
}

export const LOW_BATTERY_PERCENT = 20;

// The battery devices in use (enabled), lowest battery first and devices that never reported a level last.
export function batteryMaintenanceRows(devices, kindOf) {
  return devices
    .filter(d => d.attributes?.enabled !== false && kindOf(d) === 'battery')
    .map(d => {
      const level = batteryLevel(d);
      return {
        deviceId: d.deviceId,
        name: d.name,
        area: d.attributes?.area || d.attributes?.room || '',
        level,
        low: level !== null && level <= LOW_BATTERY_PERCENT,
        lastSeen: d.lastSeen || null,
      };
    })
    .sort((a, b) => (a.level ?? Infinity) - (b.level ?? Infinity) || a.name.localeCompare(b.name));
}
