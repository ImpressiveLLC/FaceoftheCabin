import { describe, expect, it } from "vitest";
import { kpiTileFor } from "./App.jsx";

const sensor = (attributes, state = "ONLINE") => ({
  deviceId: "synthetic-voc", name: "Living room Total VOC", type: "AIR_QUALITY_SENSOR", state, attributes,
});
const voc = { device_class: "volatile_organic_compounds_parts", unit_of_measurement: "ppb" };

describe("air quality tile source units", () => {
  it("shows the source VOC unit with readable precision and preserves device identity and raw reading", () => {
    const attributes = Object.freeze({ ...voc, airQualityIndex: 1453.2000122070312 });
    expect(kpiTileFor(sensor(attributes, "ALARM"), "F")).toMatchObject({
      label: "Living room Total VOC", deviceId: "synthetic-voc", value: "1453.2 ppb", state: "ALARM",
    });
    expect(attributes.airQualityIndex).toBe(1453.2000122070312);
  });

  it("keeps AQI unitless even beside VOC or with contradictory unit metadata", () => {
    expect(kpiTileFor(sensor({ ...voc, airQualityIndex: 1453.2 }), "C").value).toBe("1453.2 ppb");
    expect(kpiTileFor(sensor({ device_class: "aqi", unit_of_measurement: "ppb", airQualityIndex: 101.8 }), "C").value).toBe("101.8");
  });

  it("does not guess a unit for missing or unrecognized source metadata", () => {
    for (const metadata of [{}, { device_class: "aqi" }, { device_class: "volatile_organic_compounds_parts" }, { unit_of_measurement: "ppb" }]) {
      expect(kpiTileFor(sensor({ ...metadata, airQualityIndex: 12.3 }), "F").value).toBe("12.3");
    }
  });

  it("uses the reported concentration unit without converting it to ppb", () => {
    expect(kpiTileFor(sensor({ device_class: "volatile_organic_compounds", unit_of_measurement: "µg/m³", airQualityIndex: 12.3 }), "F").value).toBe("12.3 µg/m³");
  });

  it("keeps zero and tiny readings distinct from missing readings", () => {
    expect(kpiTileFor(sensor({ ...voc, airQualityIndex: 0 }), "F").value).toBe("0 ppb");
    expect(kpiTileFor(sensor({ ...voc, airQualityIndex: 0.004 }), "F").value).toBe("0.004 ppb");
    for (const airQualityIndex of [undefined, null, "", "unavailable", NaN, Infinity]) {
      expect(kpiTileFor(sensor({ ...voc, airQualityIndex }), "F").value).toBe("—");
    }
  });
});
