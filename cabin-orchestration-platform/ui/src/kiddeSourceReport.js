// W-171 bounded presentation binding, verified on #244 (2026-11-27).
// This is not a general Kidde package definition or an alarm/check-in policy.
const PARENT = "ha-cabin-binary-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-co-alarm";
const SOURCE = "ha-cabin-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-last-seen";
const ENTITY = "sensor.living_room_kidde_co_temp_and_humidity_cabin_upstairs_last_seen";
const CUE_AFTER_MS = 24 * 60 * 60 * 1000; // Proposed presentation cutoff; Nate ratification is a merge gate.

export function kiddeSourceCues(devices, now = Date.now()) {
  const siblings = devices.filter(d => d.location === "cabin" &&
    (d.deviceId === PARENT || d.attributes?.parentDeviceId === PARENT));
  const sources = siblings.filter(d => d.deviceId === SOURCE &&
    d.attributes?.entityId === ENTITY && d.attributes?.device_class === "timestamp");
  const value = sources.length === 1 ? sources[0].attributes.value : null;
  // Require a zoned source timestamp. Never fall back to registry lastSeen,
  // a repeated measurement, a browser-local date, or a future clock reading.
  const parsed = typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    ? Date.parse(value) : NaN;
  // Date.parse normalizes impossible days such as February 30; reject those too.
  const valid = Number.isFinite(parsed) && parsed <= now &&
    new Date(`${value.slice(0, 10)}T00:00:00Z`).toISOString().slice(0, 10) === value.slice(0, 10);
  const cue = !valid ? "Source freshness unknown"
    : now - parsed > CUE_AFTER_MS
      ? `Source last reported ${new Date(parsed).toISOString().replace("T", " ").replace(".000Z", "Z").replace("Z", " UTC")}`
      : null;
  return Object.fromEntries(siblings.map(d => [d.deviceId, cue]));
}
