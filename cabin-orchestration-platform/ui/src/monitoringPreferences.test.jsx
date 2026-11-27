import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, renderHook, waitFor, cleanup, within, act } from "@testing-library/react";
import { AppContext, MnSeeView } from "./App.jsx";
import { applyMonitoringPreferences, validateMonitoringPreferences, useMonitoringPreferences } from "./monitoringPreferences";

const temperature = (id, location = "cabin", state = "ONLINE") => ({
  deviceId: id, name: id, type: "TEMPERATURE_SENSOR", location, state, attributes: { temperature: 20 },
});
const snapshot = (location, cards = []) => ({ location, revision: 1, cards });
const card = (deviceId, included = true) => ({ deviceId, included });
let preferences;
beforeEach(() => {
  preferences = { cabin: snapshot("cabin"), home: snapshot("home") };
  localStorage.clear();
  vi.stubGlobal("fetch", vi.fn(async url => ({ ok: true, json: async () =>
    url.includes("monitoring-preferences") ? preferences[new URL(url).searchParams.get("location")] : {} })));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); });

function View({ devices, enabled = true, location = "cabin", reorderMode = false }) {
  return <AppContext.Provider value={{ displayConfigs: {} }}>
    <MnSeeView devices={devices} activeLocation={location} active={false} reorderMode={reorderMode}
      sharedPreferencesEnabled={enabled} />
  </AppContext.Provider>;
}
const labels = container => [...container.querySelectorAll(".kpi-grid .kpi-tile")].map(tile => tile.textContent);

describe("Monitoring read-through contract", () => {
  it("unknown preferences cannot add cards and alarms override hidden preferences", () => {
    const devices = [temperature("a"), temperature("b", "cabin", "ALARM"), temperature("c", "cabin", "CRITICAL")];
    const prefs = snapshot("cabin", [card("unknown"), card("a", false), card("b", false), card("c", false)]);
    expect(applyMonitoringPreferences(devices, prefs).map(d => d.deviceId)).toEqual(["b", "c"]);
    expect(devices).toHaveLength(3);
  });

  it.each([
    null, { location: "home", revision: 0, cards: [] }, { location: "cabin", revision: -1, cards: [] },
    snapshot("cabin", [card("a"), card("a")]), snapshot("cabin", [{ deviceId: "a" }]),
  ])("rejects malformed or wrong-location data %#", value => {
    expect(() => validateMonitoringPreferences(value, "cabin")).toThrow();
  });

  it("flag-off neither fetches preferences nor changes saved order and drag", () => {
    localStorage.setItem("order.monitoring.cabin", JSON.stringify(["b", "a"]));
    const view = render(<View devices={[temperature("a"), temperature("b")]} enabled={false} reorderMode />);
    expect(labels(view.container)[0]).toContain("b");
    expect(view.container.querySelector('[draggable="true"]')).not.toBeNull();
    expect(fetch.mock.calls.some(([url]) => url.includes("monitoring-preferences"))).toBe(false);
  });

  it("shared order ignores browser order and disables misleading local drag", async () => {
    localStorage.setItem("order.monitoring.cabin", JSON.stringify(["b", "a"]));
    const view = render(<View devices={[temperature("a"), temperature("b")]} reorderMode />);
    await waitFor(() => expect(labels(view.container)[0]).toContain("a"));
    expect(view.container.querySelector('[draggable="true"]')).toBeNull();
    expect(within(view.container).getByText(/Shared order is read-only/)).toBeTruthy();
    expect(fetch.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
  });

  it.each(["ALARM", "CRITICAL"])("hidden normal card reappears pinned on %s without refetch", async state => {
    preferences.cabin = snapshot("cabin", [card("hidden", false)]);
    const devices = [temperature("normal"), temperature("hidden")];
    const view = render(<View devices={devices} />);
    await waitFor(() => expect(view.container.querySelectorAll(".kpi-tile")).toHaveLength(1));
    const calls = fetch.mock.calls.filter(([url]) => url.includes("monitoring-preferences")).length;
    view.rerender(<View devices={[devices[0], { ...devices[1], state }]} />);
    expect(labels(view.container)[0]).toContain("hidden");
    expect(view.container.querySelectorAll(".kpi-tile")).toHaveLength(2);
    expect(fetch.mock.calls.filter(([url]) => url.includes("monitoring-preferences"))).toHaveLength(calls);
  });

  it("locations use separate preferences, unknown keys never render, unlocated cards remain", async () => {
    preferences.cabin = snapshot("cabin", [card("c", false), card("unknown"), card("unlocated", false)]);
    preferences.home = snapshot("home", [card("h"), card("unlocated", false)]);
    const unlocated = { ...temperature("unlocated"), location: null };
    const view = render(<View devices={[temperature("c"), temperature("h", "home"), unlocated]} location="both" />);
    await waitFor(() => expect(view.container.querySelectorAll(".kpi-tile")).toHaveLength(3));
    expect(labels(view.container).filter(t => t.includes("unlocated"))).toHaveLength(2);
    expect(labels(view.container).some(t => t.includes("unknown"))).toBe(false);
    expect(fetch.mock.calls.filter(([url]) => url.includes("monitoring-preferences")).map(([url]) => new URL(url).searchParams.get("location")).sort()).toEqual(["cabin", "home"]);
  });

  it("a rendered leak ALARM cannot be hidden even when raw device state says ONLINE", async () => {
    preferences.cabin = snapshot("cabin", [card("leak", false)]);
    const leak = { ...temperature("leak"), type: "WATER_LEAK_SENSOR",
      attributes: { water_leak: true, reportsFields: ["water_leak"], deviceLifecycle: "ASSIGNED" } };
    const view = render(<View devices={[temperature("normal"), leak]} />);
    await waitFor(() => expect(labels(view.container)[0]).toContain("Wet"));
    expect(labels(view.container)[0]).toContain("ALARM");
  });

  it("failed shared read falls back to old browser order with explicit status", async () => {
    localStorage.setItem("order.monitoring.cabin", JSON.stringify(["b", "a"]));
    preferences.cabin = { broken: true };
    const view = render(<View devices={[temperature("a"), temperature("b")]} />);
    await within(view.container).findByText(/preferences unavailable/);
    expect(labels(view.container)[0]).toContain("b");
  });

  it("hiding a card does not remove the source from history requests", async () => {
    preferences.cabin = snapshot("cabin", [card("a", false)]);
    const device = { ...temperature("a"), attributes: { temperature: 20, reportsFields: ["temperature"] } };
    const view = render(<View devices={[device]} />);
    await waitFor(() => expect(view.container.querySelectorAll(".kpi-tile")).toHaveLength(0));
    expect(fetch.mock.calls.some(([url]) => url.includes("reported-fields"))).toBe(true);
    expect(device.attributes.temperature).toBe(20);
  });

  it("disabled-parent group survives empty preferences and hiding does not resurrect its child", async () => {
    const parent = { deviceId: "parent", name: "Electrical group", type: "POWER_METER", location: "cabin",
      state: "ONLINE", attributes: { enabled: false, deviceLifecycle: "ASSIGNED" } };
    const child = { deviceId: "child", name: "Child watts", type: "POWER_METER", location: "cabin",
      state: "ONLINE", attributes: { enabled: true, parentDeviceId: "parent", entityId: "sensor.child",
        serviceRole: "measurement", sourceReportedFields: ["power"], power: 12 } };
    const view = render(<View devices={[parent, child]} />);
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("monitoring-preferences"))).toBe(true));
    await act(async () => { await Promise.resolve(); });
    expect(labels(view.container)).toHaveLength(1);
    expect(labels(view.container)[0]).toContain("Electrical group");
    view.unmount();
    preferences.cabin = snapshot("cabin", [card("parent", false), card("child", true)]);
    const hidden = render(<View devices={[parent, child]} />);
    await waitFor(() => expect(labels(hidden.container)).toHaveLength(0));
    expect(parent.attributes.enabled).toBe(false);
    expect(child.attributes.power).toBe(12);
  });
});

describe("read lifecycle", () => {
  it("late response from previous location cannot replace the current snapshot", async () => {
    let resolveOld;
    const fetcher = vi.fn(url => url.includes("cabin") ? new Promise(resolve => { resolveOld = resolve; })
      : Promise.resolve({ ok: true, json: async () => snapshot("home") }));
    const hook = renderHook(({ location }) => useMonitoringPreferences(true, location, "http://test", fetcher),
      { initialProps: { location: "cabin" } });
    await waitFor(() => expect(resolveOld).toBeTypeOf("function"));
    hook.rerender({ location: "home" });
    await waitFor(() => expect(hook.result.current?.data?.location).toBe("home"));
    await act(async () => resolveOld({ ok: true, json: async () => snapshot("cabin") }));
    expect(hook.result.current.data.location).toBe("home");
  });
});

describe("empty-preference golden renderer parity", () => {
  const types = ["AIR_QUALITY_SENSOR", "CAMERA", "CO2_SENSOR", "CONTACT_SENSOR", "CO_ALARM", "CO_SENSOR",
    "HUMIDITY_SENSOR", "LOCK", "MOTION_SENSOR", "POWER_METER", "SMOKE_ALARM", "TEMPERATURE_SENSOR", "THERMOSTAT",
    "WATER_LEAK_SENSOR", "WATER_PRESSURE_SENSOR", "DASHBOARD", "DISHWASHER", "DRYER", "GOOGLE_HOME_DEVICE",
    "HOME_ASSISTANT_ENTITY", "ROUTER", "UPS", "WASHING_MACHINE", "WATER_VALVE"];
  it.each(types)("preserves the rendered %s content and masking", async type => {
    const device = { ...temperature("sample"), type, attributes: { temperature: 20, humidity: 50, co2: 600,
      power: 10, energy: 1, contact: true, occupancy: true, water_leak: false, co: 0, enabled: true,
      deviceLifecycle: "ASSIGNED", reportsFields: ["temperature", "contact", "occupancy", "water_leak"] } };
    const view = render(<View devices={[device]} enabled={false} />);
    const before = labels(view.container);
    expect(before).toHaveLength(types.indexOf(type) < 15 ? 1 : 0);
    view.rerender(<View devices={[device]} />);
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("monitoring-preferences"))).toBe(true));
    await act(async () => { await Promise.resolve(); });
    expect(labels(view.container)).toEqual(before);
  });
});
