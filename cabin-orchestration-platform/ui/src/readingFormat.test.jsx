import React from "react";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AppContext, DmDeviceDetail, FamilyHubPanel, kpiTileFor } from "./App.jsx";
import { ThemeProvider } from "./ThemeProvider.jsx";
import { formatAttribute, formatReading, readingNumber, readingText } from "./readingFormat.js";

const device = (type, attributes, state = "ONLINE") => ({ deviceId: "synthetic-1", name: "Synthetic", type, state, attributes });

describe("formatReading (W-174)", () => {
  it("shows humidity as a whole percent", () => {
    expect(formatReading("humidity", 29.683223724365234)).toBe("30");
    expect(formatReading("humidity", 29.780000686645508)).toBe("30");
    expect(formatReading("humidity", "29.18999")).toBe("29");
    expect(formatReading("humidity", 0)).toBe("0");
  });

  it("rounds each listed field to its own precision", () => {
    expect(formatReading("co2", 812.97)).toBe("813");
    expect(formatReading("airQualityIndex", 1234.2000122070312)).toBe("1234.2");
    expect(formatReading("airQualityIndex", 57.8)).toBe("57.8");
    expect(formatReading("psi", 52.000000762939453)).toBe("52");
    expect(formatReading("power", 263.26)).toBe("263.26");
    expect(formatReading("energy", 119.97000122)).toBe("119.97");
    expect(formatReading("current", 0.045000001)).toBe("0.045");
    expect(formatReading("voltage", 120.30000305)).toBe("120.3");
    expect(formatReading("co", 4.5999999)).toBe("4.6");
  });

  it("keeps one fixed decimal for temperature, below zero included", () => {
    expect(formatReading("temperature", -12.34999)).toBe("-12.3");
    expect(formatReading("temperature", 20)).toBe("20.0");
    expect(formatReading("temperature", -0.04)).toBe("0.0");
  });

  it("never shows a nonzero safety reading as zero", () => {
    expect(formatReading("co", 0.004)).toBe("0.004");
    expect(formatReading("co", 0.0000123456)).toBe("0.000012");
    expect(formatReading("airQualityIndex", 0.04)).toBe("0.04");
    expect(formatReading("current", 0.0002)).toBe("0.0002");
    expect(formatReading("co2", 0.4)).toBe("0.4");
    expect(formatReading("co", 0)).toBe("0");
  });

  it("has no number for a missing or non-numeric value", () => {
    for (const value of [undefined, null, "", "  ", "null", "unavailable", NaN, Infinity, true, {}]) {
      expect(readingNumber(value)).toBeNull();
      expect(formatReading("humidity", value)).toBeNull();
      expect(readingText("humidity", value, "%")).toBe("—");
    }
  });

  it("leaves a field it does not list as it arrived", () => {
    expect(formatReading("voc_index", 101.12345)).toBe("101.12345");
    expect(formatReading("constructor", 1.25)).toBe("1.25");
  });

  it("rounds only reading attributes in the device detail view", () => {
    expect(formatAttribute("humidity", 29.683223724365234)).toBe("30");
    expect(formatAttribute("current_temperature", 9.3899999)).toBe("9.4");
    expect(formatAttribute("temperature", "unavailable")).toBe("unavailable");
    expect(formatAttribute("current", 0.0450001)).toBe("0.0450001");
    expect(formatAttribute("serial", 1.23456789)).toBe("1.23456789");
  });
});

describe("Monitoring tiles show rounded readings (W-174)", () => {
  it("shows whole percent humidity for the long values that wrapped a tile", () => {
    for (const humidity of [29.683223724365234, 29.780000686645508]) {
      expect(kpiTileFor(device("HUMIDITY_SENSOR", { humidity }), "F").value).toBe("30%");
    }
  });

  it("rounds temperature and humidity together, in either unit and below zero", () => {
    const both = device("TEMPERATURE_SENSOR", { temperature: -23.456789, humidity: 37.298765 });
    expect(kpiTileFor(both, "C").value).toBe("-23.5°C · 37%");
    expect(kpiTileFor(both, "F").value).toBe("-10.2°F · 37%");
    expect(kpiTileFor(device("THERMOSTAT", { current_temperature: 18.8888888 }), "C").value).toBe("18.9°C");
  });

  it("rounds CO2 and water pressure", () => {
    expect(kpiTileFor(device("CO2_SENSOR", { co2: 812.97 }), "F").value).toBe("813 ppm");
    expect(kpiTileFor(device("WATER_PRESSURE_SENSOR", { psi: 51.9999999 }), "F").value).toBe("52 PSI");
  });

  it("shows a dash, or leaves the part out, for a missing or non-numeric reading", () => {
    for (const humidity of [undefined, null, "null", "unavailable"]) {
      expect(kpiTileFor(device("HUMIDITY_SENSOR", { humidity }), "F").value).toBe("—");
      expect(kpiTileFor(device("TEMPERATURE_SENSOR", { temperature: 20, humidity }), "C").value).toBe("20.0°C");
    }
    expect(kpiTileFor(device("TEMPERATURE_SENSOR", { temperature: "unavailable" }), "F").value).toBe("—");
    expect(kpiTileFor(device("CO2_SENSOR", {}), "F").value).toBe("—");
  });

  it("takes alarm state from the device and leaves the stored reading untouched", () => {
    const attributes = Object.freeze({ co: 0.00412345 });
    const tile = kpiTileFor(device("CO_SENSOR", attributes, "ALARM"), "F");
    expect(tile).toMatchObject({ value: "0.0041 ppm", state: "ALARM" });
    expect(attributes.co).toBe(0.00412345);
  });
});

describe("other views of a reading (W-174)", () => {
  afterEach(() => { cleanup(); localStorage.clear(); });

  it("rounds the device detail attribute and keeps the stored value in its tooltip", () => {
    render(<DmDeviceDetail device={{ ...device("HUMIDITY_SENSOR", { humidity: 29.683223724365234 }), location: "cabin" }}
      workflows={[]} onConfigure={() => {}} onLifecycleAction={() => {}} />);
    expect(screen.getByText("30").getAttribute("title")).toBe("29.683223724365234");
  });

  it("rounds the Family Hub temperature row", () => {
    const devices = [{ ...device("TEMPERATURE_SENSOR", { temperature: 14.611111, humidity: 37.298765 }), location: "cabin" }];
    const { container } = render(
      <ThemeProvider><AppContext.Provider value={{ devices }}><FamilyHubPanel /></AppContext.Provider></ThemeProvider>);
    expect(container.querySelector(".fh-temp-val").textContent).toBe("58.3°F · 37%");
  });
});
