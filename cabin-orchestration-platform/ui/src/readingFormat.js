// W-174: how many digits a sensor reading gets when a person reads it. Display and export only: stored
// values, API payloads, the raw telemetry export, thresholds and rule evaluation never pass through here.
//
// `decimals` is the most shown; `fixed` keeps trailing zeros so a column of one measure lines up.
// A field that is not listed is returned as it arrived.
export const READING_PRECISION = {
  humidity:        { decimals: 0 },
  temperature:     { decimals: 1, fixed: true },
  co2:             { decimals: 0 },
  co:              { decimals: 2 },
  airQualityIndex: { decimals: 1 },
  psi:             { decimals: 1 },
  power:           { decimals: 2 },
  energy:          { decimals: 3 },
  current:         { decimals: 3 },
  voltage:         { decimals: 2 },
};

// A finite number from a number or a numeric string; null for anything else, including "", "null",
// "unavailable", booleans, NaN and Infinity. Number("") and Number(null) are 0, which would invent a reading.
export function readingNumber(value) {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string" || value.trim() === "") return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

// The reading as text without a unit, or null when there is no number to show. A nonzero reading never
// comes out as zero (a trace of CO must not read as none): it keeps two significant digits instead.
export function formatReading(field, value) {
  const n = readingNumber(value);
  if (n === null) return null;
  if (!Object.hasOwn(READING_PRECISION, field)) return String(value);
  const { decimals, fixed } = READING_PRECISION[field];
  const text = n.toFixed(decimals);
  if (Number(text) === 0) {
    if (n !== 0 && !fixed) return String(Number(n.toPrecision(2)));
    // Temperature crosses zero, so a value that rounds to it is shown as zero, without a minus sign.
    return (0).toFixed(fixed ? decimals : 0);
  }
  return fixed ? text : String(Number(text));
}

// A reading with its unit, as shown on screen; a dash when there is no number.
export function readingText(field, value, unit = "") {
  const text = formatReading(field, value);
  return text === null ? "—" : `${text}${unit}`;
}

// The attribute keys a device's detail view rounds. Electrical keys are not among them: they are shown as
// reported. Any other attribute is not a reading and is shown as it is.
const DETAIL_ATTRIBUTE_FIELDS = {
  temperature: "temperature", current_temperature: "temperature", humidity: "humidity",
  co2: "co2", co: "co", airQualityIndex: "airQualityIndex", psi: "psi",
};
export function formatAttribute(key, value) {
  const field = Object.hasOwn(DETAIL_ATTRIBUTE_FIELDS, key) ? DETAIL_ATTRIBUTE_FIELDS[key] : null;
  return (field && formatReading(field, value)) ?? String(value);
}
