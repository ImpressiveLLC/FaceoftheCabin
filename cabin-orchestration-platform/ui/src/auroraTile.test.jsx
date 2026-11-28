import { describe, expect, it } from "vitest";
import { kpiTileFor } from "./App.jsx";
import { auroraPercentText, isAuroraPercentDevice, AURORA_FRESHNESS_NOTE } from "./auroraTile";

const aurora = (over = {}, attrs = {}) => ({ deviceId: "ha-cabin-sensor-aurora-visibility-visibility",
  type: "HOME_ASSISTANT_ENTITY", name: "Aurora visibility Visibility", location: "cabin", state: "ONLINE",
  attributes: { entityId: "sensor.aurora_visibility_visibility", unit_of_measurement: "%", value: "2",
    enabled: true, ...attrs }, ...over });

describe("aurora percentage tile", () => {
  it("builds a tile with the reported percentage and an unknown-freshness note", () => {
    const t = kpiTileFor(aurora(), "F");
    expect(t).toMatchObject({ label: "Aurora visibility", value: "2%", state: "ONLINE", footnote: AURORA_FRESHNESS_NOTE });
    expect(t.deviceId).toBe("ha-cabin-sensor-aurora-visibility-visibility");
  });
  it("shows zero as a reported zero and missing or invalid as unavailable, never 0", () => {
    expect(kpiTileFor(aurora({}, { value: "0" }), "F").value).toBe("0%");
    for (const v of [undefined, null, "", "unavailable", "unknown", "abc", "101", "-1", "NaN"]) {
      expect(kpiTileFor(aurora({}, { value: v }), "F").value).toBe("unavailable");
    }
    expect(auroraPercentText(37.5)).toBe("37.5%");
  });
  it("does not bind unrelated percent sensors, other sites, wrong entity or unit, or a Daikin Aurora name", () => {
    expect(kpiTileFor(aurora({ deviceId: "ha-cabin-sensor-battery-percent" }), "F")).toBeNull();
    expect(kpiTileFor(aurora({ location: "home" }), "F")).toBeNull();
    expect(kpiTileFor(aurora({}, { entityId: "sensor.other" }), "F")).toBeNull();
    expect(kpiTileFor(aurora({}, { unit_of_measurement: "ppm" }), "F")).toBeNull();
    expect(kpiTileFor(aurora({ deviceId: "ha-cabin-climate-daikin-aurora", name: "Daikin Aurora" }), "F")).toBeNull();
    expect(isAuroraPercentDevice(null)).toBe(false);
  });
  it("the alert entity gets no tile (no state value is exposed)", () => {
    expect(kpiTileFor(aurora({ deviceId: "ha-cabin-binary-sensor-aurora-visibility-visibility-alert" },
      { entityId: "binary_sensor.aurora_visibility_visibility_alert", unit_of_measurement: undefined }), "F")).toBeNull();
  });
});
