import { useEffect, useState } from "react";

export const isMonitoringAlarm = device => ["ALARM", "CRITICAL"].includes(device.state);

export function validateMonitoringPreferences(value, location) {
  if (value?.location !== location || !Number.isSafeInteger(value?.revision) || value.revision < 0
      || !Array.isArray(value.cards)) throw new Error("Invalid Monitoring preferences");
  const seen = new Set();
  for (const card of value.cards) {
    if (typeof card?.deviceId !== "string" || !card.deviceId || typeof card.included !== "boolean"
        || seen.has(card.deviceId)) throw new Error("Invalid Monitoring card preference");
    seen.add(card.deviceId);
  }
  return value;
}

/** Only operates on existing projected cards. Unknown preferences cannot add a card. */
export function applyMonitoringPreferences(items, preferences, idOf = d => d.deviceId, alarm = isMonitoringAlarm) {
  const byId = new Map(preferences.cards.map((card, index) => [card.deviceId, { ...card, index }]));
  return items.filter(item => alarm(item) || byId.get(idOf(item))?.included !== false)
    .map((item, index) => ({ item, index }))
    .sort((a, b) => Number(alarm(b.item)) - Number(alarm(a.item))
      || (byId.get(idOf(a.item))?.index ?? Infinity) - (byId.get(idOf(b.item))?.index ?? Infinity)
      || a.index - b.index)
    .map(({ item }) => item);
}

/** Read-only and opt-in. Failed or superseded requests never supply a preference snapshot. */
export function useMonitoringPreferences(enabled, location, apiBase, fetcher) {
  const [result, setResult] = useState(null);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const controller = new AbortController();
    setResult(null);
    const timer = setTimeout(() => controller.abort(), 10000);
    Promise.resolve().then(() => fetcher(`${apiBase}/api/devices/monitoring-preferences?location=${encodeURIComponent(location)}`,
      { signal: controller.signal }))
      .then(response => {
        if (!response.ok) throw new Error("Monitoring preferences unavailable");
        return response.json();
      })
      .then(data => {
        if (!cancelled) setResult({ location, apiBase, fetcher, data: validateMonitoringPreferences(data, location) });
      })
      .catch(() => { if (!cancelled) setResult({ location, apiBase, fetcher, error: true }); })
      .finally(() => clearTimeout(timer));
    return () => { cancelled = true; clearTimeout(timer); controller.abort(); };
  }, [enabled, location, apiBase, fetcher]);
  return enabled && result?.location === location && result.apiBase === apiBase && result.fetcher === fetcher
    ? result : null;
}
