import React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, within } from "@testing-library/react";
import { AppContext, MnSeeView } from "./App.jsx";
import { kiddeSourceCues } from "./kiddeSourceReport";

const parent = "ha-cabin-binary-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-co-alarm";
const sourceId = "ha-cabin-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-last-seen";
const now = Date.parse("2026-11-28T00:00:00Z");
const sibling = (deviceId = "voc", extra = {}) => ({ deviceId, name: deviceId, location: "cabin",
  state: "ONLINE", type: "AIR_QUALITY_SENSOR", attributes: { parentDeviceId: parent,
    enabled: true, deviceLifecycle: "ASSIGNED", airQualityIndex: 1453.2,
    device_class: "volatile_organic_compounds_parts", unit_of_measurement: "ppb" }, ...extra });
const source = (value = "2026-11-06T09:40:08-06:00") => ({ deviceId: sourceId, location: "cabin",
  type: "HOME_ASSISTANT_ENTITY", state: "ONLINE", lastSeen: new Date(now).toISOString(),
  attributes: { parentDeviceId: parent, enabled: true, deviceLifecycle: "ASSIGNED",
    entityId: "sensor.living_room_kidde_co_temp_and_humidity_cabin_upstairs_last_seen",
    device_class: "timestamp", value } });
const oldCue = "Source last reported 2026-11-06 15:40:08 UTC";

describe("Kidde source timestamp presentation", () => {
  it("uses source time despite a fresh poll, and includes the exact parent and siblings", () => {
    expect(kiddeSourceCues([sibling(), sibling(parent, { attributes: {} }), source()], now))
      .toMatchObject({ voc: oldCue, [parent]: oldCue });
  });
  it.each(["2026-11-27T01:00:00Z", "2026-11-27T00:00:00Z"])("has no cue at or inside 24h: %s", value => {
    expect(kiddeSourceCues([sibling(), source(value)], now).voc).toBeNull();
  });
  it("shows the timestamp just beyond the proposed cutoff", () => {
    expect(kiddeSourceCues([sibling(), source("2026-11-26T23:59:59Z")], now).voc)
      .toBe("Source last reported 2026-11-26 23:59:59 UTC");
  });
  it.each([null, "", "unavailable", "2026-11-06", "2026-11-06T15:40:08", "2026-13-01T00:00:00Z",
    "2026-02-30T00:00:00Z", "2026-11-29T00:00:00Z", 1793979608000])("unknown for unusable source time %s", value => {
    expect(kiddeSourceCues([sibling(), source(value)], now).voc).toBe("Source freshness unknown");
  });
  it("missing and ambiguous sources stay unknown instead of borrowing another unit's time", () => {
    expect(kiddeSourceCues([sibling()], now).voc).toBe("Source freshness unknown");
    expect(kiddeSourceCues([sibling(), source(), source()], now).voc).toBe("Source freshness unknown");
    const other = source(); other.attributes.parentDeviceId = "other-unit";
    expect(kiddeSourceCues([sibling(), other], now).voc).toBe("Source freshness unknown");
  });
  it("does not match renamed labels, other locations, wrong source class or wrong entity", () => {
    expect(kiddeSourceCues([sibling("other", { name: "Kidde", attributes: {} }),
      sibling("home", { location: "home" }), source()], now)).not.toHaveProperty("other");
    expect(kiddeSourceCues([sibling("home", { location: "home" }), source()], now)).not.toHaveProperty("home");
    for (const [key, value] of [["entityId", "sensor.other_last_seen"], ["device_class", "date"]]) {
      const wrong = source(); wrong.attributes[key] = value;
      expect(kiddeSourceCues([sibling(), wrong], now).voc).toBe("Source freshness unknown");
    }
  });
});

describe("Monitoring source cue", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.spyOn(Date, "now").mockReturnValue(now);
    vi.stubGlobal("fetch", vi.fn(async url => ({ ok: true, json: async () => String(url).includes("monitoring-preferences") ? { location: "cabin", revision: 1, cards: [] } : {} })));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); });
  function view(devices, shared = false) {
    return <AppContext.Provider value={{ displayConfigs: {} }}>
      <MnSeeView devices={devices} activeLocation="cabin" active={false} sharedPreferencesEnabled={shared} />
    </AppContext.Provider>;
  }
  it.each([false, true])("renders the source cue and preserves ALARM (shared=%s)", async shared => {
    const alarm = sibling("alarm", { type: "CO_ALARM", state: "ALARM" });
    let container;
    await act(async () => { ({ container } = render(view([sibling(), alarm, source()], shared))); });
    const tiles = [...container.querySelectorAll(".kpi-tile")];
    const voc = tiles.find(t => t.querySelector(".kpi-label")?.textContent === "voc");
    const co = tiles.find(t => t.querySelector(".kpi-label")?.textContent === "alarm");
    expect(within(voc).getByText("1453.2 ppb")).toBeTruthy();
    expect(within(voc).getByText(oldCue)).toBeTruthy();
    expect(within(co).getByText(oldCue)).toBeTruthy();
    expect(co.className).toContain("kpi-state-alarm");
    expect(co.querySelector(".state-badge").textContent).toBe("ALARM");
    expect(tiles).toHaveLength(2); // timestamp source does not need its own visible tile
  });
  it("updates unknown to old to recent as source records arrive, without changing ONLINE", async () => {
    let container, rerender;
    await act(async () => { ({ container, rerender } = render(view([sibling()]))); });
    expect(container.querySelector(".kpi-source-cue").textContent).toBe("Source freshness unknown");
    await act(async () => { rerender(view([sibling(), source()])); });
    expect(container.querySelector(".kpi-source-cue").textContent).toBe(oldCue);
    await act(async () => { rerender(view([sibling(), source("2026-11-27T23:00:00Z")])); });
    expect(container.querySelector(".kpi-source-cue")).toBeNull();
    expect(container.querySelector(".kpi-tile .state-badge").textContent).toBe("ONLINE");
  });
});
