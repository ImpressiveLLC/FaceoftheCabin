import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SensorHistoryPanel, outdoorCellText, zonedTime } from "./App.jsx";

// W-174 slice 2: Sensor History table, peaks, outdoor cells and chart CSV. Synthetic values only.
const sensor = (field) => ({ deviceId: `synthetic-${field}`, name: "Synthetic", type: "TEMPERATURE_SENSOR",
  state: "ONLINE", location: "cabin", attributes: { enabled: true, reportsFields: [field] } });

const ok = (body) => Promise.resolve({ ok: true, json: async () => body });

function stubHistory(field, history, outdoor) {
  const createObjectURL = vi.fn().mockReturnValue("blob:mock");
  vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL: vi.fn() });
  vi.stubGlobal("fetch", vi.fn((url) => {
    if (url.includes("/reported-fields")) return ok({ [`synthetic-${field}`]: [field] });
    if (url.includes("/telemetry-history")) return ok(history(url));
    if (url.includes("/outdoor-history")) return ok(outdoor ?? { points: [] });
    return ok({});
  }));
  return createObjectURL;
}

const dayAnswer = (points) => () => ({ requestedDays: 30, effectiveDays: 30, clamped: false, points });

async function csvLines(createObjectURL) {
  fireEvent.click(screen.getByRole("button", { name: "Download CSV" }));
  const blob = createObjectURL.mock.calls.at(-1)[0];
  const text = await new Promise(resolve => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.readAsText(blob);
  });
  return text.split("\n");
}

describe("Sensor History rounds readings for display and export (W-174)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const day = "2026-08-25T12:00:00Z";
  const point = (avg) => ({ day, avg, min: avg, max: avg, sampleCount: 3 });

  it("shows and exports whole percent humidity, outdoor values included", async () => {
    const createObjectURL = stubHistory("humidity", dayAnswer([point(29.683223724365234)]),
      { stations: ["TEST"], points: [{ day, station: "TEST", rhPct: 76.66666666, rhMinPct: 60.4, rhMaxPct: 90.6, sampleCount: 24 }] });
    render(<SensorHistoryPanel devices={[sensor("humidity")]} apiBase="http://cabin" tempUnit="F" location="cabin" />);

    expect(await screen.findByText("30% (n=3)")).toBeTruthy();
    expect(await screen.findByText("77% (low 60%, high 91%) (n=24)")).toBeTruthy();
    const lines = await csvLines(createObjectURL);
    expect(lines).toContain(`2026-08-25,"Synthetic",30,3,no,%`);
    expect(lines).toContain(`2026-08-25,"Outdoor humidity (TEST)",77,24,no,%`);
  });

  it("rounds a temperature below zero after converting it, in the table and the CSV", async () => {
    const createObjectURL = stubHistory("temperature", dayAnswer([point(-23.456789)]));
    render(<SensorHistoryPanel devices={[sensor("temperature")]} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText("-10.2°F (n=3)")).toBeTruthy();
    expect(await csvLines(createObjectURL)).toContain(`2026-08-25,"Synthetic",-10.2,3,no,degF`);
  });

  it("never shows or exports a nonzero CO average as zero", async () => {
    const createObjectURL = stubHistory("co", dayAnswer([point(0.00412345)]));
    render(<SensorHistoryPanel devices={[sensor("co")]} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText("0.0041 ppm (n=3)")).toBeTruthy();
    expect(await csvLines(createObjectURL)).toContain(`2026-08-25,"Synthetic",0.0041,3,no,ppm`);
  });

  it("rounds the intraday average, high, low and peak, and leaves a missing one empty", async () => {
    const at = new Date(Math.floor(Date.now() / 1000) * 1000).toISOString();
    const points = [{ day: at, avg: 29.18, min: null, max: 29.780000686645508, maxAt: at, sampleCount: 2 }];
    const createObjectURL = stubHistory("humidity", (url) => url.includes("bucket=")
      ? { requestedDays: 30, effectiveDays: 30, clamped: false, bucket: "5m", timezone: "America/Chicago", points }
      : { requestedDays: 30, effectiveDays: 30, clamped: false, points: [] });
    render(<SensorHistoryPanel devices={[sensor("humidity")]} apiBase="http://cabin" tempUnit="F" />);
    fireEvent.change(await screen.findByLabelText(/^resolution$/i), { target: { value: "5m" } });

    expect(await screen.findByText("29% (max 30%, n=2)")).toBeTruthy();
    expect(screen.getByRole("list", { name: /today's peak/i }).textContent)
      .toBe(`Synthetic: today's peak 30% at ${zonedTime(at, "America/Chicago").label}`);
    await waitFor(() => expect(screen.getByRole("button", { name: "Download CSV" }).disabled).toBe(false));
    expect((await csvLines(createObjectURL))[1]).toContain(`,"Synthetic",29,,30,`);
  });

  it("rounds an outdoor temperature cell by the field it belongs to", () => {
    const cell = { value: 4.4444444, min: null, max: null, n: 2, partial: false };
    expect(outdoorCellText(cell, v => v, "°C")).toBe("4.4°C (n=2)");
    expect(outdoorCellText(cell, v => v, "%", "humidity")).toBe("4% (n=2)");
  });
});
