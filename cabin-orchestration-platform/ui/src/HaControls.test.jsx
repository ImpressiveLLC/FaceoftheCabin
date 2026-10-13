import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { powerToggleTarget, mechRoomTemperature, DmDeviceRow, OnTheWayButton } from "./App.jsx";

// W-27/W-28 (2026-10-02): dashboard heater controls via POST /api/ha/services.

const heater = (attrs = {}) => ({
  deviceId: "z2m-heater_mech_room", name: "heater_mech_room", type: "POWER_METER",
  state: "ONLINE", location: "cabin", attributes: { state: "OFF", ...attrs },
});

const okFetch = () => vi.fn(() => Promise.resolve({
  ok: true, status: 200, json: () => Promise.resolve({ accepted: true }),
}));

describe("powerToggleTarget", () => {
  it("maps a Zigbee plug to its switch.<friendly_name> entity with its reported state", () => {
    expect(powerToggleTarget(heater({ state: "ON" }))).toEqual({ entityId: "switch.heater_mech_room", isOn: true });
  });

  it("uses an HA-discovered switch's own entity id", () => {
    expect(powerToggleTarget({
      deviceId: "ha-cabin-switch-breaker-box", location: "cabin",
      attributes: { entityId: "switch.breaker_box", state: "OFF" },
    })).toEqual({ entityId: "switch.breaker_box", isOn: false });
  });

  it("gives nothing without a real on/off, for valves/locks, Home devices, or candidates", () => {
    expect(powerToggleTarget(heater({ state: undefined }))).toBeNull();
    expect(powerToggleTarget({ ...heater(), deviceId: "z2m-main_water_valve" })).toBeNull();
    expect(powerToggleTarget({ ...heater(), location: "home" })).toBeNull();
    expect(powerToggleTarget(heater({ deviceLifecycle: "CANDIDATE" }))).toBeNull();
    expect(powerToggleTarget({ deviceId: "ha-cabin-sensor-x", location: "cabin",
      attributes: { entityId: "sensor.x", state: "ON" } })).toBeNull();
  });
});

describe("DmPowerToggle (via DmDeviceRow)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("only renders where the caller passes auth (the See view)", () => {
    render(<DmDeviceRow device={heater()} onClick={() => {}} />);
    expect(screen.queryByRole("button", { name: /turn on/i })).toBeNull();
  });

  it("posts switch.turn_on, flips optimistically, and doesn't select the row", async () => {
    const authedFetch = okFetch();
    const onClick = vi.fn();
    render(<DmDeviceRow device={heater()} onClick={onClick} auth={{ authedFetch }} />);

    fireEvent.click(screen.getByRole("button", { name: /turn on/i }));

    expect(screen.getByRole("button", { name: /turn off/i }).getAttribute("aria-pressed")).toBe("true");
    await waitFor(() => expect(authedFetch).toHaveBeenCalledTimes(1));
    const [url, opts] = authedFetch.mock.calls[0];
    expect(url).toMatch(/\/api\/ha\/services$/);
    expect(JSON.parse(opts.body)).toEqual({ domain: "switch", service: "turn_on", entity_id: "switch.heater_mech_room" });
    expect(onClick).not.toHaveBeenCalled();
  });

  it("snaps back and explains when the backend refuses", async () => {
    const authedFetch = vi.fn(() => Promise.resolve({
      ok: false, status: 502, json: () => Promise.resolve({ accepted: false }),
    }));
    render(<DmDeviceRow device={heater()} onClick={() => {}} auth={{ authedFetch }} />);

    fireEvent.click(screen.getByRole("button", { name: /turn on/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: /turn on/i }).getAttribute("title"))
      .toMatch(/Not changed: Home Assistant didn't accept/));
  });

  it("re-syncs to the next poll's reported state", () => {
    const { rerender } = render(<DmDeviceRow device={heater()} onClick={() => {}} auth={{ authedFetch: okFetch() }} />);
    rerender(<DmDeviceRow device={heater({ state: "ON" })} onClick={() => {}} auth={{ authedFetch: okFetch() }} />);
    expect(screen.getByRole("button", { name: /turn off/i }).getAttribute("aria-pressed")).toBe("true");
  });
});

describe("OnTheWayButton", () => {
  afterEach(() => { cleanup(); localStorage.clear(); });

  const devices = [{ deviceId: "z2m-temp_mech_room", attributes: { temperature: 5 } }];

  it("finds the mech room temperature", () => {
    expect(mechRoomTemperature(devices)).toBe(5);
    expect(mechRoomTemperature([])).toBeNull();
  });

  it("turns on input_boolean.on_the_way_to_cabin and confirms with the mech room temp", async () => {
    localStorage.setItem("tempUnit", "F");
    const authedFetch = okFetch();
    render(<OnTheWayButton auth={{ authedFetch }} devices={devices} />);

    fireEvent.click(screen.getByRole("button", { name: /on the way/i }));

    expect((await screen.findByRole("status")).textContent).toBe("Preheat started. Mech room is 41.0°F.");
    expect(JSON.parse(authedFetch.mock.calls[0][1].body)).toEqual({
      domain: "input_boolean", service: "turn_on", entity_id: "input_boolean.on_the_way_to_cabin",
    });
  });

  it("asks to sign in on a 401 instead of claiming success", async () => {
    const authedFetch = vi.fn(() => Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({}) }));
    render(<OnTheWayButton auth={{ authedFetch }} devices={devices} />);

    fireEvent.click(screen.getByRole("button", { name: /on the way/i }));

    expect((await screen.findByRole("status")).textContent).toBe("Couldn't start preheat: Sign in to control devices");
  });
});
