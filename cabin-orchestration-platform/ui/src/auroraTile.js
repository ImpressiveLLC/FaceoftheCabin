// C2 aurora, bounded first slice (Cowork, Nate sign-off 2026-11-28).
// Binds only the verified Home Assistant Aurora percentage entity at the Cabin. A bare "%" never
// qualifies. The provider observation and forecast times are not exposed by the HA bridge, so the
// tile never claims a current forecast: it says freshness is unknown. The binary alert entity is not
// tiled here because the API exposes no state value for it (a missing state is not "off").
export const AURORA_PERCENT_DEVICE_ID = "ha-cabin-sensor-aurora-visibility-visibility";
export const AURORA_PERCENT_ENTITY_ID = "sensor.aurora_visibility_visibility";
export const AURORA_FRESHNESS_NOTE = "NOAA estimate, freshness unknown";

export function isAuroraPercentDevice(device) {
  return !!device && device.deviceId === AURORA_PERCENT_DEVICE_ID && device.location === "cabin" &&
    device.attributes?.entityId === AURORA_PERCENT_ENTITY_ID && device.attributes?.unit_of_measurement === "%";
}

// Finite number from 0 to 100 only. Missing, empty, text and out-of-range are unavailable, never 0.
export function auroraPercentText(raw) {
  if (raw === null || raw === undefined || (typeof raw === "string" && raw.trim() === "")) return "unavailable";
  const n = typeof raw === "number" ? raw : (typeof raw === "string" && /^\d+(\.\d+)?$/.test(raw.trim()) ? Number(raw) : NaN);
  return Number.isFinite(n) && n >= 0 && n <= 100 ? `${Number.isInteger(n) ? n : n.toFixed(1)}%` : "unavailable";
}
