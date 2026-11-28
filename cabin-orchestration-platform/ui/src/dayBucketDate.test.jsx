import React from "react";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { GuestDashboard, SensorHistoryPanel, dayBucketDate, dayBucketLabel } from "./App.jsx";

// W-175: a day bucket is dated in the zone the server bucketed in (UTC), whatever zone the viewer is in.
// Synthetic values only.
const sensor = { deviceId: "synthetic-humidity", name: "Synthetic", type: "TEMPERATURE_SENSOR",
  state: "ONLINE", location: "cabin", attributes: { enabled: true, reportsFields: ["humidity"] } };

// The day the defect was reported, then a year rollover; oldest first, as the server answers.
const DAYS = ["2026-11-27T00:00:00Z", "2026-12-31T00:00:00Z", "2027-01-01T00:00:00Z"];
const points = DAYS.map((day, i) => ({ day, avg: 40 + i, min: 40 + i, max: 40 + i, sampleCount: 3, partial: false }));
const outdoor = { stations: ["TEST"],
  points: DAYS.map((day, i) => ({ day, station: "TEST", rhPct: 70 + i, sampleCount: 24, partial: false })) };

const ok = (body) => Promise.resolve({ ok: true, json: async () => body });
function stubApi() {
  vi.stubGlobal("fetch", vi.fn((url) => {
    if (url.includes("/reported-fields")) return ok({ [sensor.deviceId]: ["humidity"] });
    if (url.includes("/telemetry-history")) return ok({ requestedDays: 60, effectiveDays: 60, clamped: false, points });
    if (url.includes("/outdoor-history")) return ok(outdoor);
    return Promise.resolve({ ok: false, status: 403, json: async () => ({}) });
  }));
}

// A calendar date written in the runner's locale, built at local noon so no zone conversion can move it.
const written = (y, m, d) => new Date(y, m - 1, d, 12).toLocaleDateString();

describe.each([
  // [viewer zone, the day of the month on that viewer's own clock at 2026-11-27T00:00:00Z]
  ["America/Chicago", 26], // UTC-6 on this date
  ["Pacific/Auckland", 27], // east of UTC
])("day bucket dates for a viewer in %s (W-175)", (viewerZone, localDayOfMonth) => {
  const originalZone = process.env.TZ;
  beforeAll(() => { process.env.TZ = viewerZone; });
  afterAll(() => {
    if (originalZone === undefined) delete process.env.TZ; else process.env.TZ = originalZone;
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("runs on that viewer's clock", () => {
    expect(new Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(viewerZone);
    expect(new Date(DAYS[0]).getDate()).toBe(localDayOfMonth);
  });

  it("keeps each bucket's UTC date, across a year rollover", () => {
    expect(DAYS.map(dayBucketDate)).toEqual(["2026-11-27", "2026-12-31", "2027-01-01"]);
    expect(DAYS.map(dayBucketLabel)).toEqual([written(2026, 11, 27), written(2026, 12, 31), written(2027, 1, 1)]);
  });

  it("dates the table rows that way and says they are UTC days", async () => {
    stubApi();
    render(<SensorHistoryPanel devices={[sensor]} apiBase="http://cabin" tempUnit="F" location="cabin" />);

    expect(await screen.findByText("70% (n=24)")).toBeTruthy();
    const rows = screen.getAllByRole("row");
    expect(within(rows[0]).getByText("Date (UTC)")).toBeTruthy();
    // Newest first; the indoor and outdoor values of one bucket share its row.
    expect(rows.slice(1).map(r => [...r.querySelectorAll("td")].map(td => td.textContent))).toEqual([
      [written(2027, 1, 1), "42% (n=3)", "72% (n=24)"],
      [written(2026, 12, 31), "41% (n=3)", "71% (n=24)"],
      [written(2026, 11, 27), "40% (n=3)", "70% (n=24)"],
    ]);
    expect(screen.getByText(/Days are UTC days, here and in the CSV export/)).toBeTruthy();
  });

  it("dates the sensor and outdoor baseline CSV rows that way and names the zone in the header", async () => {
    stubApi();
    const createObjectURL = vi.fn().mockReturnValue("blob:mock");
    vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL: vi.fn() });
    render(<SensorHistoryPanel devices={[sensor]} apiBase="http://cabin" tempUnit="F" location="cabin" />);

    expect(await screen.findByText("70% (n=24)")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Download CSV" }));
    const text = await new Promise(resolve => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.readAsText(createObjectURL.mock.calls.at(-1)[0]);
    });
    expect(text.split("\n")).toEqual([
      "date_utc,device,avg,count,partial,unit",
      `2026-11-27,"Synthetic",40,3,no,%`,
      `2026-11-27,"Outdoor humidity (TEST)",70,24,no,%`,
      `2026-12-31,"Synthetic",41,3,no,%`,
      `2026-12-31,"Outdoor humidity (TEST)",71,24,no,%`,
      `2027-01-01,"Synthetic",42,3,no,%`,
      `2027-01-01,"Outdoor humidity (TEST)",72,24,no,%`,
    ]);
  });

  it("dates the guest page's latest daily reading that way", async () => {
    stubApi();
    render(<GuestDashboard token="synthetic-token" />);

    expect(await screen.findByText(`avg 42 on ${written(2027, 1, 1)} (UTC day)`)).toBeTruthy();
  });
});
