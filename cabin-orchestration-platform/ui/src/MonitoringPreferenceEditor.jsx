import React, { useEffect, useRef, useState } from "react";
import { isMonitoringAlarm, validateMonitoringPreferences } from "./monitoringPreferences";

// Existing DeviceType identifiers; all temperature cards receive the safety confirmation.
const SAFETY_TYPES = new Set(["SMOKE_ALARM", "CO_ALARM", "CO_SENSOR", "WATER_LEAK_SENSOR", "WATER_VALVE",
  "LOCK", "TEMPERATURE_SENSOR", "THERMOSTAT"]);
export const HIDE_CONFIRMATION = "Hide this tile from Monitoring? Alerts stay active. The device stays enabled and its history is kept.";

export function monitoringWriteReason(auth) {
  if (auth?.demo) return "Demo links are view-only.";
  if (auth?.guest || auth?.guestToken) return "Guest links are view-only.";
  if (auth?.managedUserRole === "VIEWER") return "Viewer sessions are view-only.";
  if (!auth?.signedIn || !auth?.authedFetch) return "Sign in to change shared Monitoring preferences.";
  return "";
}

export function monitoringBaseCards(entries, snapshot) {
  const ids = new Set(entries.map(entry => entry.tile.deviceId));
  const cards = snapshot.cards.filter(card => ids.has(card.deviceId));
  const known = new Set(cards.map(card => card.deviceId));
  return [...cards, ...entries.filter(entry => !known.has(entry.tile.deviceId))
    .map(entry => ({ deviceId: entry.tile.deviceId, included: true }))];
}

export function reorderedMonitoringCards(base, movableIds, orderedIds) {
  if (orderedIds.length !== movableIds.length || new Set(orderedIds).size !== movableIds.length
      || orderedIds.some(id => !movableIds.includes(id))) throw new Error("Order changed; review and retry.");
  const movable = new Set(movableIds);
  const cards = new Map(base.map(card => [card.deviceId, card]));
  let next = 0;
  return base.map(card => movable.has(card.deviceId) ? cards.get(orderedIds[next++]) : card);
}

export function MonitoringPreferenceEditor({ entries, location, apiBase, auth, preferences }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [draft, setDraft] = useState(null);
  const pending = useRef(null);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setBusy(false); setMessage(""); setDraft(null);
    return () => { generation.current++; pending.current?.abort(); pending.current = null; };
  }, [location, apiBase, auth?.authedFetch]);

  const snapshot = preferences?.data;
  const reason = monitoringWriteReason(auth) || (!snapshot ? "Shared preferences must load before changes can be saved." : "");
  const base = snapshot ? monitoringBaseCards(entries, snapshot) : entries.map(entry => ({ deviceId: entry.tile.deviceId, included: true }));
  const byId = new Map(entries.map(entry => [entry.tile.deviceId, entry]));
  const movable = base.filter(card => card.included && !isMonitoringAlarm(byId.get(card.deviceId).tile)).map(card => card.deviceId);
  const orderKey = JSON.stringify([snapshot?.revision, base, movable]);
  const orderedIds = draft?.key === orderKey ? draft.ids : movable;
  const hasDraft = draft?.key === orderKey && orderedIds.some((id, index) => id !== movable[index]);

  async function save(cards) {
    if (reason || pending.current) return;
    const current = generation.current;
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true); setMessage("");
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await auth.authedFetch(`${apiBase}/api/devices/monitoring-preferences?location=${encodeURIComponent(location)}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" }, signal: controller.signal,
        body: JSON.stringify({ expectedRevision: snapshot.revision, cards }),
      });
      if (current !== generation.current) return;
      if (response.status === 409) {
        preferences.refresh();
        throw new Error("Preferences changed elsewhere. Review the refreshed view, then retry.");
      }
      if (response.status === 401 || response.status === 403) throw new Error("This session cannot save changes. Sign in with write access.");
      if (!response.ok) throw new Error("Preferences were not saved. Refresh the view before retrying.");
      const saved = validateMonitoringPreferences(await response.json(), location);
      if (current !== generation.current) return;
      preferences.accept(saved); setDraft(null); setMessage("Preference saved.");
    } catch (error) {
      if (current === generation.current) setMessage(error.name === "AbortError"
        ? "Save could not be confirmed. Refresh the view before retrying." : error.message);
    } finally {
      clearTimeout(timeout);
      if (current === generation.current) { pending.current = null; setBusy(false); }
    }
  }

  function toggle(card) {
    const entry = byId.get(card.deviceId);
    if (card.included && isMonitoringAlarm(entry.tile)) return;
    if (card.included && SAFETY_TYPES.has(entry.d.type) && !window.confirm(HIDE_CONFIRMATION)) return;
    save(base.map(value => value.deviceId === card.deviceId ? { ...value, included: !value.included } : value));
  }

  function move(index, offset) {
    const ids = [...orderedIds];
    [ids[index], ids[index + offset]] = [ids[index + offset], ids[index]];
    setDraft({ key: orderKey, ids });
  }

  return <details className="monitoring-preferences-editor">
    <summary>Monitoring visibility and order</summary>
    <p>Hide does not delete the device, its history or its display settings.</p>
    {reason && <p>{reason}</p>}
    {message && <p role="status">{message}</p>}
    {busy && <p role="status">Saving shared preferences…</p>}
    <ul>{base.map(card => {
      const entry = byId.get(card.deviceId);
      const alarm = isMonitoringAlarm(entry.tile);
      return <li key={card.deviceId}>
        <span>{entry.tile.label}</span>{" "}
        <span>{alarm ? "Active alarm stays visible." : card.included ? "Shown on Monitoring" : "Hidden from Monitoring"}</span>{" "}
        <button type="button" disabled={!!reason || busy || (alarm && card.included)}
          aria-label={`${card.included ? "Hide" : "Show"} ${entry.tile.label} ${card.included ? "from" : "on"} Monitoring`}
          onClick={() => toggle(card)}>{card.included ? "Hide from Monitoring" : "Show on Monitoring"}</button>
      </li>;
    })}</ul>
    <p>Shared order. Active alarms stay pinned; hidden cards keep their saved positions.</p>
    <ol>{orderedIds.map((id, index) => <li key={id}>
      {byId.get(id).tile.label}{" "}
      <button type="button" aria-label={`Move ${byId.get(id).tile.label} up`} disabled={!!reason || busy || index === 0}
        onClick={() => move(index, -1)}>Up</button>{" "}
      <button type="button" aria-label={`Move ${byId.get(id).tile.label} down`} disabled={!!reason || busy || index === orderedIds.length - 1}
        onClick={() => move(index, 1)}>Down</button>
    </li>)}</ol>
    <button type="button" disabled={!!reason || busy || !hasDraft}
      onClick={() => save(reorderedMonitoringCards(base, movable, orderedIds))}>Save order</button>
    <button type="button" disabled={busy} onClick={() => { setDraft(null); preferences.refresh(); }}>Refresh shared preferences</button>
  </details>;
}
