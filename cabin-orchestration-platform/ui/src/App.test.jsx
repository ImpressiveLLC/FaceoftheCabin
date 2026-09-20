import React from "react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor, within } from "@testing-library/react";
import { isCameraEvent, mergeHubLocations, buildCameraEventsUrl, cameraEventsWindowLabel, CAMERA_EVENTS_WINDOWS, groupCameraEvents, classifyMediaFetchStatus, isLocationDeployed, formatPresenceSignals, formatArmedTitle, cameraHealthLabel, allLocationsLabel, checkinStatusLabel, groupDevices, filterDeviceManagerDevices, resolveDeviceManagerFilter, LIFECYCLE_FILTER_OPTIONS, DEFAULT_LIFECYCLE_FILTER, buildOrderedDeviceGroups, migrateLegacyDeviceOrder, reorderIds, WORKFLOW_BY_TYPE, deviceLifecycleState, humanizeRuleId, automationAlertSteps, alertLevelFor, deriveNavAlertLevels, navAlertLevelsFor, AppContext, FamilyHubPanel, FamilyConfigPanel, RulesPanel, DmDeviceDetail, DmEditForm, DmDeviceRow, workflowsForDevice, WorkflowRulesCard, CameraEventsPanel, CameraNotifyToggle, DeviceDiscoveryOverlay, CameraEventClip, kpiTileFor, MnSeeView, countParentDevices, DeviceManagerPanel, SensorHistoryPanel, HelpdeskPanel, GuestDashboard, DmRemoveView, MagicLinkLanding, OptimizationOpportunitiesCard, PlatformImportFlow, PendingImportRow, OpportunityCard, useAutomationAlerts, AlertControls,
PresenceActivityView, formatActiveTime, formatDaysSince, formatPresenceDay,
mergeStatusCheckItems } from "./App.jsx";
import { ThemeProvider } from "./ThemeProvider.jsx";

// Covers the actual reported bug this session ("Camera Events" showing
// device logs instead of camera activity) -- see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4a and
// isCameraEvent's own comment in App.jsx.
describe("isCameraEvent", () => {
  it("accepts DETECTION_* events", () => {
    expect(isCameraEvent({ eventType: "DETECTION_NEW" })).toBe(true);
    expect(isCameraEvent({ eventType: "DETECTION_UPDATE" })).toBe(true);
    expect(isCameraEvent({ eventType: "DETECTION_END" })).toBe(true);
  });

  it("accepts MOTION_* events", () => {
    expect(isCameraEvent({ eventType: "MOTION_ON" })).toBe(true);
    expect(isCameraEvent({ eventType: "MOTION_OFF" })).toBe(true);
  });

  it("rejects non-camera device event types -- this is the actual bug being fixed", () => {
    expect(isCameraEvent({ eventType: "STATE_CHANGE" })).toBe(false);
    expect(isCameraEvent({ eventType: "ALARM" })).toBe(false);
    expect(isCameraEvent({ eventType: "LEAK_DETECTED" })).toBe(false);
  });

  it("does not false-positive on a type that merely contains the camera prefix mid-string", () => {
    expect(isCameraEvent({ eventType: "NOT_A_MOTION_EVENT" })).toBe(false);
  });

  it("handles a missing/undefined eventType without throwing", () => {
    expect(isCameraEvent({})).toBe(false);
    expect(isCameraEvent({ eventType: undefined })).toBe(false);
  });
});

// 2026-08-24: a missing clip read the same regardless of whether the
// camera has a continuous feed (front_door, driveway) or a genuinely
// intermittent one (home_aldrich_front) -- see CAMERA_FEED_CONTINUOUS's
// own comment in App.jsx for the live diagnostics behind this split.
describe("CameraEventClip — missing-clip wording reflects feed continuity", () => {
  afterEach(cleanup);

  function missingFetch() {
    return vi.fn().mockResolvedValue({ ok: false, status: 404 });
  }

  it("uses confident wording for a continuous-feed camera (front_door)", async () => {
    render(<CameraEventClip authedFetch={missingFetch()} clipUrl="http://x/clip" cameraName="front_door" />);
    expect(await screen.findByText(/this camera usually has continuous footage/i)).toBeTruthy();
  });

  it("uses confident wording for driveway too", async () => {
    render(<CameraEventClip authedFetch={missingFetch()} clipUrl="http://x/clip" cameraName="driveway" />);
    expect(await screen.findByText(/this camera usually has continuous footage/i)).toBeTruthy();
  });

  it("uses the original hedged wording for an intermittent-feed camera (home_aldrich_front)", async () => {
    render(<CameraEventClip authedFetch={missingFetch()} clipUrl="http://x/clip" cameraName="home_aldrich_front" />);
    expect(await screen.findByText(/frigate only keeps recordings for a limited time/i)).toBeTruthy();
  });

  it("defaults to the hedged wording for an unrecognized camera name", async () => {
    render(<CameraEventClip authedFetch={missingFetch()} clipUrl="http://x/clip" cameraName="some_future_camera" />);
    expect(await screen.findByText(/frigate only keeps recordings for a limited time/i)).toBeTruthy();
  });
});

// 2026-08-25: LocationMonitoringSection used to render KPI tiles in a
// fixed per-type-bucket sequence (pressure, thermostats, temp sensors,
// smoke, energy, locks, cameras) regardless of any saved reorder --
// useDraggableOrder's persisted order was computed and stored by a
// separate vertical-list-only reorder view, but nothing in the real grid
// ever read it. kpiTileFor is the pure per-device-type mapping pulled out
// so the grid could iterate in saved order instead.
describe("kpiTileFor", () => {
  it("maps a known device type to its tile shape", () => {
    const pressure = { deviceId: "p1", type: "WATER_PRESSURE_SENSOR", state: "ONLINE", attributes: { psi: 52 } };
    expect(kpiTileFor(pressure, "F")).toMatchObject({ label: "Water Pressure", value: "52 PSI", state: "ONLINE" });
  });

  it("combines temperature and humidity into one value", () => {
    const sensor = { deviceId: "t1", name: "Kitchen", type: "TEMPERATURE_SENSOR", state: "ONLINE",
      attributes: { temperature: 20, humidity: 45 } };
    expect(kpiTileFor(sensor, "C").value).toBe("20°C · 45%");
  });

  it("returns null for a device type with no KPI tile -- unchanged from before this fix", () => {
    expect(kpiTileFor({ deviceId: "x", type: "ROUTER", state: "ONLINE" }, "F")).toBeNull();
  });

  // 2026-09-19 (user: put z2m-motion_entry on the Monitoring dashboard): motion,
  // door-contact and water-leak sensors never had a tile. Shapes below are the
  // real inventory (z2m-motion_entry, z2m-door_front_contact, z2m-leak_alarm_*).
  describe("safety sensors (motion, door contact, water leak)", () => {
    const motion = (attrs = {}, extra = {}) => ({ deviceId: "z2m-motion_entry", name: "motion_entry_movement_detect",
      type: "MOTION_SENSOR", state: "ONLINE", location: "cabin", attributes: { occupancy: false, battery: 100, ...attrs }, ...extra });
    const contact = (attrs = {}) => ({ deviceId: "z2m-door_front_contact", name: "door_front_contact",
      type: "CONTACT_SENSOR", state: "ONLINE", location: "cabin", attributes: { contact: false, battery: 87, ...attrs } });
    const leak = (attrs = {}, extra = {}) => ({ deviceId: "z2m-leak_alarm_bathroom", name: "leak_alarm_bathroom",
      type: "WATER_LEAK_SENSOR", state: "ONLINE", location: "cabin", attributes: { water_leak: false, ...attrs }, ...extra });

    it("shows a signed-in viewer Clear / Motion", () => {
      expect(kpiTileFor(motion(), "F", {}, { signedIn: true })).toMatchObject({ label: "motion_entry_movement_detect", value: "Clear", state: "ONLINE" });
      expect(kpiTileFor(motion({ occupancy: true }), "F", {}, { signedIn: true }).value).toBe("Motion");
    });

    it("shows a signed-out viewer only that the sensor is online and its battery -- never the reading", () => {
      for (const occupancy of [true, false]) {
        const tile = kpiTileFor(motion({ occupancy }), "F");
        expect(tile.value).toBe("100% battery");
        expect(JSON.stringify(tile)).not.toMatch(/motion"|clear/i);
      }
      expect(kpiTileFor(motion({ battery: undefined }), "F").value).toBe("Online");
    });

    it("treats a door contact the same way: Open/Closed only when signed in, and the icon follows the state", () => {
      expect(kpiTileFor(contact({ contact: false }), "F", {}, { signedIn: true })).toMatchObject({ value: "Open" });
      expect(kpiTileFor(contact({ contact: true }), "F", {}, { signedIn: true })).toMatchObject({ value: "Closed" });
      expect(kpiTileFor(contact(), "F").value).toBe("87% battery");
    });

    it("shows a leak reading to everyone, and raises ALARM only when wet", () => {
      expect(kpiTileFor(leak(), "F")).toMatchObject({ value: "Dry", state: "ONLINE" });
      expect(kpiTileFor(leak({ water_leak: true }), "F")).toMatchObject({ value: "Wet", state: "ALARM" });
    });

    it("shows unknown, not Dry, for a leak sensor that has never reported (Zigbee2MQTT sends null)", () => {
      expect(kpiTileFor(leak({ water_leak: "null" }), "F").value).toBe("—");
      expect(kpiTileFor(leak({ water_leak: null }), "F").value).toBe("—");
    });

    it("gives no tile to a candidate, ignored or deferred device", () => {
      for (const deviceLifecycle of ["CANDIDATE", "IGNORED", "DEFERRED"]) {
        expect(kpiTileFor(motion({ deviceLifecycle }), "F", {}, { signedIn: true })).toBeNull();
        expect(kpiTileFor(leak({ deviceLifecycle }), "F")).toBeNull();
      }
    });

    it("gives no tile to a Home Assistant duplicate that carries none of the Zigbee fields", () => {
      const duplicate = { deviceId: "ha-cabin-binary-sensor-motion-entry-occu", name: "motion entry occupancy", type: "MOTION_SENSOR",
        state: "ONLINE", location: "cabin", attributes: { enabled: true } };
      expect(kpiTileFor(duplicate, "F", {}, { signedIn: true })).toBeNull();
      expect(kpiTileFor({ ...duplicate, type: "WATER_LEAK_SENSOR" }, "F")).toBeNull();
    });
  });

  // 2026-08-27 (user report): these four types were added for Sensor
  // History charting but fell through kpiTileFor's default (no tile at
  // all) -- Kidde's humidity/CO2/air-quality never showed in Monitoring
  // even though the same reading correctly charted in Sensor History.
  it("gives a humidity-only device its own tile, not folded into a temperature tile", () => {
    const device = { deviceId: "h1", name: "Kidde Humidity", type: "HUMIDITY_SENSOR", state: "ONLINE",
      attributes: { humidity: 57 } };
    expect(kpiTileFor(device, "F")).toMatchObject({ label: "Kidde Humidity", value: "57%", state: "ONLINE" });
  });

  it("maps a CO2 sensor to a ppm-labeled tile", () => {
    const device = { deviceId: "c1", name: "Kidde CO2", type: "CO2_SENSOR", state: "ONLINE",
      attributes: { co2: 633 } };
    expect(kpiTileFor(device, "F").value).toBe("633 ppm");
  });

  it("maps an air quality sensor to its index value", () => {
    const device = { deviceId: "a1", name: "Kidde AQI", type: "AIR_QUALITY_SENSOR", state: "ONLINE",
      attributes: { airQualityIndex: 58.3 } };
    expect(kpiTileFor(device, "F").value).toBe("58.3");
  });

  it("maps a CO_ALARM device the same way as SMOKE_ALARM", () => {
    const device = { deviceId: "co-alarm", name: "Kidde CO Alarm", type: "CO_ALARM", state: "ALARM" };
    expect(kpiTileFor(device, "F")).toMatchObject({ value: "ALARM", state: "ALARM" });
  });

  // D13 (Service-Level Data Lineage), WSJF bug #4: a curated display_label
  // for this device's specific measurement wins over the raw device.name --
  // the exact "Upstairs CO" vs. the real, much longer HA friendly_name case.
  it("uses the curated display_label for a CO sensor's tile title when one is set", () => {
    const device = { deviceId: "kidde-co-level", name: "Kidde CO Temp and Humidity Cabin Upstairs CO Level",
      type: "CO_SENSOR", state: "ONLINE", attributes: { co: 3 } };
    const reportingRelationships = { "kidde-co-level": [{ semanticField: "co", displayLabel: "Upstairs CO" }] };

    expect(kpiTileFor(device, "F", reportingRelationships).label).toBe("Upstairs CO");
  });

  it("falls back to device.name when no display_label has been curated yet", () => {
    const device = { deviceId: "kidde-co-level", name: "Kidde CO Level", type: "CO_SENSOR", state: "ONLINE",
      attributes: { co: 3 } };

    expect(kpiTileFor(device, "F", {}).label).toBe("Kidde CO Level");
    expect(kpiTileFor(device, "F").label).toBe("Kidde CO Level"); // reportingRelationships also fully optional
  });

  it("uses the curated display_label for a humidity-only device's tile title too", () => {
    const device = { deviceId: "kidde-humidity", name: "Kidde CO Temp and Humidity Cabin Upstairs Humidity",
      type: "HUMIDITY_SENSOR", state: "ONLINE", attributes: { humidity: 57 } };
    const reportingRelationships = { "kidde-humidity": [{ semanticField: "humidity", displayLabel: "Upstairs Humidity" }] };

    expect(kpiTileFor(device, "F", reportingRelationships).label).toBe("Upstairs Humidity");
  });

  // D15 (Energy Device Ontology, 2026-09-05): before this fix, every
  // POWER_METER tile showed the literal, hardcoded label "Energy" (never
  // device.name or a curated label) and read device.attributes.state_w,
  // a field that doesn't exist on either real smart plug -- confirmed live
  // the real keys are power/energy/current/voltage. This is the exact user
  // complaint that drove D15: a device with no name, no location, and no
  // indication of what the number means.
  it("combines power and energy into one value, primary metric first", () => {
    const device = { deviceId: "z2m-heater_mech_room", name: "heater_mech_room", type: "POWER_METER",
      state: "ONLINE", attributes: { power: 117.7, energy: 119.97, current: 1.42, voltage: 121.8 } };
    expect(kpiTileFor(device, "F").value).toBe("117.7 W · 119.97 kWh");
  });

  it("uses the curated display_label for a power meter's tile title when one is set", () => {
    const device = { deviceId: "z2m-heater_mech_room", name: "heater_mech_room", type: "POWER_METER",
      state: "ONLINE", attributes: { power: 117.7, energy: 119.97 } };
    const reportingRelationships = { "z2m-heater_mech_room": [{ semanticField: "power", displayLabel: "Mech Room Heater Power" }] };

    expect(kpiTileFor(device, "F", reportingRelationships).label).toBe("Mech Room Heater Power");
  });

  it("falls back to device.name, not a hardcoded 'Energy' literal, when no display_label has been curated yet", () => {
    const device = { deviceId: "z2m-smart_switch_breaker_box", name: "smart_switch @ breaker_box",
      type: "POWER_METER", state: "ONLINE", attributes: { power: 0, energy: 0 } };
    expect(kpiTileFor(device, "F", {}).label).toBe("smart_switch @ breaker_box");
  });

  it("shows an em dash when a power meter has no readings yet, not '— W'", () => {
    const device = { deviceId: "z2m-new-plug", name: "New Plug", type: "POWER_METER", state: "ONLINE", attributes: {} };
    expect(kpiTileFor(device, "F").value).toBe("—");
  });
});

// 2026-08-25: replaces the Grafana "View Sensor History" link-out, which
// proved unreliable/unfit for its actual purpose (documented humidity/temp
// evidence for an active insurance claim -- see docs/MAINTENANCE.md Known
// Issues). Backed by the new GET /api/events/telemetry-history endpoint
// (CabinEventService.dailyAggregates()).
describe("SensorHistoryPanel", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  // "reportsFields" here is the empirical, observed-data fact this panel
  // now actually gates on (GET /api/events/reported-fields,
  // CabinEventService.reportedFieldsByDevice()) -- not
  // DeviceType.telemetryFields()'s static per-type guess, which turned out
  // wrong for a real device (z2m-temp_outside_lowest, model SNZB-02LD, has
  // never once logged humidity despite being typed the same as combo
  // sensors that do). Fixtures below double as both the "attributes" a
  // device carries AND the source for the mocked /reported-fields response
  // via reportedFieldsFetch(), so a test that wants "this device has never
  // logged humidity" states that once, not in two disagreeing places.
  const sensors = [
    { deviceId: "z2m-humid_mech", name: "Mech Room", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin",
      attributes: { enabled: true, reportsFields: ["humidity", "temperature"] } },
    { deviceId: "z2m-humid_kitchen", name: "Kitchen", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin",
      attributes: { enabled: true, reportsFields: ["humidity", "temperature"] } },
  ];
  const points = [
    { day: "2026-08-24T00:00:00Z", avg: 70, min: 65, max: 75, sampleCount: 12 },
    { day: "2026-08-25T00:00:00Z", avg: 75.2, min: 70, max: 80, sampleCount: 8 },
  ];

  // Builds a fetch mock that answers /reported-fields from the given
  // devices' own attributes.reportsFields (so it can never silently
  // disagree with the fixture), and everything else (telemetry-history)
  // from telemetryFn(url) -- defaulting to a constant response for tests
  // that don't care which device/field a particular call was for.
  function reportedFieldsFetch(deviceList, telemetryFn = () => points) {
    const fieldsMap = Object.fromEntries(deviceList.map(d => [d.deviceId, d.attributes.reportsFields]));
    return vi.fn((url) => {
      if (url.includes("/reported-fields")) {
        return Promise.resolve({ ok: true, json: async () => fieldsMap });
      }
      return Promise.resolve({ ok: true, json: async () => telemetryFn(url) });
    });
  }

  it("renders nothing when the location has no sensors that report any chartable field", () => {
    const { container } = render(<SensorHistoryPanel devices={[]} apiBase="http://cabin" tempUnit="F" />);
    expect(container.firstChild).toBeNull();
  });

  // 2026-08-27: the primary fix the user reported -- a device typed the
  // same as a real combo sensor but that has never actually logged a
  // given field (or is disabled) must not be offered as an option for it.
  // enabled:false here mirrors what production actually looked like: every
  // HA-duplicate/dead entity cluttering the picker (temp_kitchen's own
  // "Temperature"/"Humidity" HA duplicates, the Liebherr "Loonie Mc
  // Frigerton" zone/setpoint entities, a dead Kidde entity) was disabled.
  it("excludes a device that has never actually logged the selected field, and a disabled device, even if their type/attributes claim it", async () => {
    const outsideNeverHumidity = { deviceId: "z2m-outside", name: "Outside", type: "TEMPERATURE_SENSOR",
      state: "ONLINE", location: "cabin", attributes: { enabled: true, reportsFields: ["humidity", "temperature"] } };
    const disabledDuplicate = { deviceId: "ha-kitchen-humidity-dup", name: "Kitchen Humidity (HA duplicate)",
      type: "HUMIDITY_SENSOR", state: "ONLINE", location: "cabin", attributes: { enabled: false, reportsFields: ["humidity"] } };
    const allDevices = [...sensors, outsideNeverHumidity, disabledDuplicate];
    // The empirical response is the ground truth here: "Outside" never has
    // a humidity entry at all, unlike its attributes.reportsFields claim --
    // this is the exact z2m-temp_outside_lowest shape confirmed live.
    const fieldsMap = {
      "z2m-humid_mech": ["humidity", "temperature"],
      "z2m-humid_kitchen": ["humidity", "temperature"],
      "z2m-outside": ["temperature"],
      "ha-kitchen-humidity-dup": ["humidity"],
    };
    vi.stubGlobal("fetch", vi.fn((url) => url.includes("/reported-fields")
      ? Promise.resolve({ ok: true, json: async () => fieldsMap })
      : Promise.resolve({ ok: true, json: async () => points })));
    render(<SensorHistoryPanel devices={allDevices} apiBase="http://cabin" tempUnit="F" />);

    await screen.findByRole("button", { name: "Mech Room" });
    expect(screen.queryByRole("button", { name: "Outside" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Kitchen Humidity (HA duplicate)" })).toBeNull();
  });

  // Found live 2026-08-27: `devices` arrives from a parent fetch that
  // starts empty and populates moments later (same as everywhere else in
  // this app), not synchronously like every other test fixture above
  // assumes. The very first render locked `field`'s lazy useState default
  // to the plain "temperature" fallback (since availableFields was still
  // empty), and the old reset effect -- keyed only on `field` -- never
  // fired again once real data arrived because `field` itself never
  // changed, leaving the panel stuck showing Temperature with an empty,
  // unrecoverable selection until a person happened to touch the Field
  // dropdown themselves. Reproduces that exact prop sequence.
  it("recovers to Humidity with every device selected once devices load in after an initial empty render", async () => {
    const fetchMock = reportedFieldsFetch(sensors);
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<SensorHistoryPanel devices={[]} apiBase="http://cabin" tempUnit="F" />);

    rerender(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(2));
    expect(screen.getByLabelText(/^field$/i).value).toBe("humidity");
    expect(screen.getByRole("button", { name: "Mech Room" }).className).toContain("selected");
    expect(screen.getByRole("button", { name: "Kitchen" }).className).toContain("selected");
  });

  // 2026-08-27: this is "step one" of the user's own request -- picking a
  // field defaults to every device that reports it selected as a group
  // ("select all the devices that log humidity"), not just the first one.
  it("defaults to selecting every humidity-reporting device and fetches each one's history", async () => {
    const fetchMock = reportedFieldsFetch(sensors);
    vi.stubGlobal("fetch", fetchMock);
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(2));
    const urls = fetchMock.mock.calls.map(c => c[0]);
    expect(urls.some(u => u.includes("deviceId=z2m-humid_mech") && u.includes("field=humidity") && u.includes("days=30"))).toBe(true);
    expect(urls.some(u => u.includes("deviceId=z2m-humid_kitchen") && u.includes("field=humidity") && u.includes("days=30"))).toBe(true);
  });

  it("renders real day rows from the response, most recent first", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch([sensors[0]]));
    render(<SensorHistoryPanel devices={[sensors[0]]} apiBase="http://cabin" tempUnit="F" />);

    const rows = await screen.findAllByRole("row");
    // header row + 2 data rows, most recent (8/25) first
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByText("75.2% (n=8)")).toBeTruthy();
    expect(within(rows[2]).getByText("70.0% (n=12)")).toBeTruthy();
  });

  // 2026-08-27: the user flagged that a wide multi-device table with only
  // an average per cell hides whether a reading came from dozens of real
  // samples or one stray point -- exactly the kind of question an
  // insurance inspector would ask. Sample count is now part of the cell.
  it("shows the sample count alongside each average, not just the bare value", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch([sensors[0]]));
    render(<SensorHistoryPanel devices={[sensors[0]]} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText("75.2% (n=8)")).toBeTruthy();
    expect(screen.getByText("70.0% (n=12)")).toBeTruthy();
  });

  it("converts Celsius to Fahrenheit for display when the field is temperature and unit is F", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch([sensors[0]],
      () => [{ day: "2026-08-25T00:00:00Z", avg: 20, min: 18, max: 22, sampleCount: 5 }]));
    render(<SensorHistoryPanel devices={[sensors[0]]} apiBase="http://cabin" tempUnit="F" />);
    fireEvent.change(await screen.findByLabelText(/^field$/i), { target: { value: "temperature" } });

    expect(await screen.findByText("68.0°F (n=5)")).toBeTruthy(); // 20C -> 68F
  });

  it("shows an honest empty state instead of a blank table when there's no history yet", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch([sensors[0]], () => []));
    render(<SensorHistoryPanel devices={[sensors[0]]} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText(/no humidity history for the selected devices/i)).toBeTruthy();
  });

  it("re-fetches with the new range when Range is changed", async () => {
    const fetchMock = reportedFieldsFetch([sensors[0]]);
    vi.stubGlobal("fetch", fetchMock);
    render(<SensorHistoryPanel devices={[sensors[0]]} apiBase="http://cabin" tempUnit="F" />);
    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(1));

    fireEvent.change(screen.getByLabelText(/^range$/i), { target: { value: "90" } });
    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(2));
    expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history")).at(-1)[0]).toContain("days=90");
  });

  // "step two": a real multi-select toggle -- click a device chip to
  // select/highlight it, click again to un-highlight, "Select all"/"Clear"
  // for the group action, all scoped to the currently chosen field.
  it("lets a device be toggled out of the selection, and re-fetches with only the remaining devices", async () => {
    const fetchMock = reportedFieldsFetch(sensors);
    vi.stubGlobal("fetch", fetchMock);
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(2));

    fireEvent.click(screen.getByRole("button", { name: "Kitchen" }));

    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(3));
    const lastUrl = fetchMock.mock.calls.at(-1)[0];
    expect(lastUrl).toContain("deviceId=z2m-humid_mech");
    expect(screen.getByRole("button", { name: "Mech Room" }).className).toContain("selected");
    expect(screen.getByRole("button", { name: "Kitchen" }).className).not.toContain("selected");
  });

  it("Select all restores every matching device after some were toggled off, Clear removes them all", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
    await screen.findByRole("button", { name: "Mech Room" });

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(await screen.findByText(/select at least one device/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Select all" }));
    expect(screen.getByRole("button", { name: "Mech Room" }).className).toContain("selected");
    expect(screen.getByRole("button", { name: "Kitchen" }).className).toContain("selected");
  });

  it("renders a legend and one table column per selected device when more than one is selected", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

    const headerRow = (await screen.findAllByRole("row"))[0];
    expect(within(headerRow).getByText("Mech Room")).toBeTruthy();
    expect(within(headerRow).getByText("Kitchen")).toBeTruthy();
    const legend = document.querySelector(".sensor-history-legend");
    expect(within(legend).getByText("Mech Room")).toBeTruthy();
    expect(within(legend).getByText("Kitchen")).toBeTruthy();
  });

  // D13 (Service-Level Data Lineage), WSJF bug #4: a curated display_label
  // for THIS field wins over the device's own name in both the legend and
  // the table header -- the exact "temp_kitchen"/"temp_mech_room" mislabel
  // the bug report was about.
  it("uses a curated display_label instead of the device name in the legend and table header when one is set", async () => {
    const fieldsMap = Object.fromEntries(sensors.map(d => [d.deviceId, d.attributes.reportsFields]));
    const reportingRelationships = {
      "z2m-humid_mech": [{ semanticField: "humidity", displayLabel: "Mech Room Humidity" }],
      "z2m-humid_kitchen": [{ semanticField: "humidity", displayLabel: "Kitchen Humidity" }],
    };
    vi.stubGlobal("fetch", vi.fn((url) => {
      if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, json: async () => fieldsMap });
      if (url.includes("/reporting-relationships")) return Promise.resolve({ ok: true, json: async () => reportingRelationships });
      return Promise.resolve({ ok: true, json: async () => points });
    }));

    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

    const headerRow = (await screen.findAllByRole("row"))[0];
    expect(within(headerRow).getByText("Mech Room Humidity")).toBeTruthy();
    expect(within(headerRow).getByText("Kitchen Humidity")).toBeTruthy();
    expect(within(headerRow).queryByText("Mech Room")).toBeFalsy();
  });

  // 2026-08-27: closes the gap docs/MAINTENANCE.md flagged -- "Kidde's CO/CO2
  // history... aren't wired into this picker yet". Kidde's HA-discovered
  // entities are CO2_SENSOR/AIR_QUALITY_SENSOR/CO_SENSOR (not
  // TEMPERATURE_SENSOR), each its own separate device (one physical reading
  // per HA entity, unlike the Zigbee sensor's combined temp+humidity).
  const kiddeSensors = [
    { deviceId: "ha-cabin-sensor-kidde-co2", name: "Kidde CO2 Level", type: "CO2_SENSOR", state: "ONLINE", location: "cabin",
      attributes: { enabled: true, reportsFields: ["co2"] } },
    { deviceId: "ha-cabin-sensor-kidde-aqi", name: "Kidde Air Quality Index", type: "AIR_QUALITY_SENSOR", state: "ONLINE", location: "cabin",
      attributes: { enabled: true, reportsFields: ["airQualityIndex"] } },
    { deviceId: "ha-cabin-sensor-kidde-co", name: "Kidde CO Level", type: "CO_SENSOR", state: "ONLINE", location: "cabin",
      attributes: { enabled: true, reportsFields: ["co"] } },
  ];

  it("offers CO2/air-quality/CO as Field options, not just temperature/humidity, when Kidde devices are present", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch([...sensors, ...kiddeSensors], () => []));
    render(<SensorHistoryPanel devices={[...sensors, ...kiddeSensors]} apiBase="http://cabin" tempUnit="F" />);

    const fieldSelect = await screen.findByLabelText(/^field$/i);
    expect(within(fieldSelect).getByText("CO₂")).toBeTruthy();
    expect(within(fieldSelect).getByText("Air Quality Index")).toBeTruthy();
    expect(within(fieldSelect).getByText("CO")).toBeTruthy();
  });

  it("defaults the Field picker to a CO2 device's only real field, not the Zigbee temperature/humidity options", async () => {
    const fetchMock = reportedFieldsFetch(kiddeSensors, () => []);
    vi.stubGlobal("fetch", fetchMock);
    render(<SensorHistoryPanel devices={kiddeSensors} apiBase="http://cabin" tempUnit="F" />);

    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(1));
    expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))[0][0]).toContain("deviceId=ha-cabin-sensor-kidde-co2");
    expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))[0][0]).toContain("field=co2");
    const fieldSelect = screen.getByLabelText(/^field$/i);
    expect(within(fieldSelect).queryByText("Temperature")).toBeNull();
    expect(within(fieldSelect).queryByText("Humidity")).toBeNull();
  });

  it("switching field re-scopes the selection and fetches only devices that report the new field", async () => {
    const fetchMock = reportedFieldsFetch([...sensors, ...kiddeSensors], () => []);
    vi.stubGlobal("fetch", fetchMock);
    render(<SensorHistoryPanel devices={[...sensors, ...kiddeSensors]} apiBase="http://cabin" tempUnit="F" />);
    // baseline: both humidity-reporting Zigbee sensors
    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(2));
    fetchMock.mockClear();

    fireEvent.change(screen.getByLabelText(/^field$/i), { target: { value: "co" } });

    await waitFor(() => expect(fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))).toHaveLength(1));
    const call = fetchMock.mock.calls.filter(c => c[0].includes("telemetry-history"))[0][0];
    expect(call).toContain("deviceId=ha-cabin-sensor-kidde-co");
    expect(call).toContain("field=co");
  });

  it("renders a Kidde CO2 reading with a ppm unit, not the Zigbee %/°F units", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch(kiddeSensors,
      () => [{ day: "2026-08-25T00:00:00Z", avg: 620, min: 590, max: 650, sampleCount: 40 }]));
    render(<SensorHistoryPanel devices={kiddeSensors} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText("620.0 ppm (n=40)")).toBeTruthy();
  });

  // 2026-08-27: the user asked for the reading count to be its own real
  // column "for pivot type reporting on export" -- a wide table with a
  // repeated avg column per device (the on-screen table's own shape)
  // isn't actually pivotable in Excel/Sheets without unpivoting by hand
  // first. Long/tidy format (one row per date+device) is what a
  // PivotTable expects as-is.
  it("downloads a CSV in long/tidy format with count as its own column, not folded into the average", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
    const createObjectURL = vi.fn().mockReturnValue("blob:mock");
    vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL: vi.fn() });
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
    await screen.findAllByText("75.2% (n=8)");

    fireEvent.click(screen.getByRole("button", { name: "Download CSV" }));

    expect(createObjectURL).toHaveBeenCalledOnce();
    const blob = createObjectURL.mock.calls[0][0];
    // jsdom's Blob has no working .text()/Response interop -- FileReader is
    // the one Blob-reading API jsdom actually implements faithfully.
    const text = await new Promise(resolve => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.readAsText(blob);
    });
    const lines = text.split("\n");
    // Built from the same Date().toLocaleDateString() the app itself
    // uses, not a hardcoded string -- that format is locale/timezone
    // dependent, and hardcoding it here previously broke on any runner
    // whose local timezone shifts a UTC-midnight timestamp to the
    // previous calendar day.
    const day1 = new Date("2026-08-24T00:00:00Z").toLocaleDateString();
    const day2 = new Date("2026-08-25T00:00:00Z").toLocaleDateString();
    expect(lines[0]).toBe("date,device,avg,count");
    expect(lines).toContain(`${day1},"Mech Room",70.00,12`);
    expect(lines).toContain(`${day2},"Kitchen",75.20,8`);
  });

  // The user reported not knowing what "(n=…)" meant at all until asking --
  // it must be explained on screen, not just present.
  it("explains what the (n=…) count means on screen", async () => {
    vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
    render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

    expect(await screen.findByText(/how many individual readings were averaged/i)).toBeTruthy();
  });

  // D16 (Reporting Topics IA, ratified 2026-09-05): the field/measurement
  // dropdown alone risked becoming a sprawling 15+-item list as the platform
  // grows -- the Topic tab-strip is the resolution, a fixed 5-tab layer
  // above the (now per-Topic-scoped) Field picker, 1:1 with the ratified
  // Topic table.
  describe("D16 Topic tab-strip", () => {
    it("renders all five ratified Topics, defaults to Comfort & Air, and disables Occupancy as coming soon", async () => {
      vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
      render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);

      await screen.findByRole("tab", { name: /comfort & air/i });
      expect(screen.getByRole("tab", { name: /comfort & air/i }).getAttribute("aria-selected")).toBe("true");
      expect(screen.getByRole("tab", { name: /security & presence/i })).toBeTruthy();
      expect(screen.getByRole("tab", { name: /^energy$/i })).toBeTruthy();
      expect(screen.getByRole("tab", { name: /alert history/i })).toBeTruthy();
      expect(screen.getByRole("tab", { name: /occupancy/i }).disabled).toBe(true);
      // Unchanged pre-D16 behavior: Comfort & Air's own default field is still Humidity.
      expect(screen.getByLabelText(/^field$/i).value).toBe("humidity");
    });

    it("clicking a Topic with no chartable fields at this location shows an honest empty state, not a blank picker", async () => {
      vi.stubGlobal("fetch", reportedFieldsFetch(sensors)); // sensors only report humidity/temperature (comfort_air)
      render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
      await screen.findByRole("tab", { name: /comfort & air/i });

      fireEvent.click(screen.getByRole("tab", { name: /energy/i }));

      expect(await screen.findByText(/no devices at this location report a field under this topic yet/i)).toBeTruthy();
      expect(screen.queryByLabelText(/^field$/i)).toBeNull();
    });

    // D15/D16: voltage/current are the one pair genuinely gated on the
    // reporting device's own DeviceType (POWER_METER only) -- everything
    // else in TOPIC_BY_FIELD is unconditional. This is the live regression
    // D16 fixed (a contact sensor's own battery voltage previously showed
    // up mislabeled as Energy) expressed as a Topic-tab test.
    it("Energy tab offers Power/Energy/Voltage/Current, but a battery-powered sensor's own voltage stays out of the device list", async () => {
      const powerMeter = { deviceId: "z2m-heater", name: "Heater", type: "POWER_METER", state: "ONLINE", location: "cabin",
        attributes: { enabled: true, reportsFields: ["power", "energy", "voltage", "current"] } };
      const batterySensor = { deviceId: "z2m-door", name: "Door", type: "CONTACT_SENSOR", state: "ONLINE", location: "cabin",
        attributes: { enabled: true, reportsFields: ["voltage"] } };
      const fieldsMap = { "z2m-heater": ["power", "energy", "voltage", "current"], "z2m-door": ["voltage"] };
      const reportingRelationships = {
        "z2m-heater": [
          { semanticField: "power", reportsTo: "energy" },
          { semanticField: "energy", reportsTo: "energy" },
          { semanticField: "voltage", reportsTo: "energy" },
          { semanticField: "current", reportsTo: "energy" },
        ],
        "z2m-door": [{ semanticField: "voltage", reportsTo: null }],
      };
      vi.stubGlobal("fetch", vi.fn((url) => {
        if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, json: async () => fieldsMap });
        if (url.includes("/reporting-relationships")) return Promise.resolve({ ok: true, json: async () => reportingRelationships });
        return Promise.resolve({ ok: true, json: async () => [] });
      }));
      render(<SensorHistoryPanel devices={[powerMeter, batterySensor]} apiBase="http://cabin" tempUnit="F" />);
      await screen.findByRole("tab", { name: /^energy$/i });

      fireEvent.click(screen.getByRole("tab", { name: /^energy$/i }));

      const fieldSelect = await screen.findByLabelText(/^field$/i);
      expect(within(fieldSelect).getByText("Power")).toBeTruthy();
      expect(within(fieldSelect).getByText("Voltage")).toBeTruthy();
      // Defaults to Power (first Energy field) -- only the real power meter reports it.
      expect(screen.getByRole("button", { name: "Heater" })).toBeTruthy();
      expect(screen.queryByRole("button", { name: "Door" })).toBeNull();

      fireEvent.change(fieldSelect, { target: { value: "voltage" } });
      await waitFor(() => expect(screen.getByRole("button", { name: "Heater" }).className).toContain("selected"));
      // The battery sensor also emits a raw "voltage" reading, but its
      // reporting-relationship resolves no Topic for it (not a POWER_METER)
      // -- it must never appear as a selectable device under Energy.
      expect(screen.queryByRole("button", { name: "Door" })).toBeNull();
    });

    it("Alert History tab shows currently active alerts from GET /api/alerts/active", async () => {
      const activeAlertsSnapshot = {
        generatedAt: "2026-09-05T12:00:00Z",
        alerts: [
          { alertId: "a1", sourceDeviceId: "leak_mech_room", sourceName: "Mech Room Leak", location: "cabin",
            condition: "water_leak", severity: "CRITICAL", evidenceAt: "2026-09-05T11:55:00Z",
            title: "Water leak detected", detail: "Leak sensor tripped" },
        ],
        counts: { CRITICAL: 1 },
      };
      const fieldsMap = Object.fromEntries(sensors.map(d => [d.deviceId, d.attributes.reportsFields]));
      vi.stubGlobal("fetch", vi.fn((url) => {
        if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, json: async () => fieldsMap });
        if (url.includes("/alerts/active")) return Promise.resolve({ ok: true, json: async () => activeAlertsSnapshot });
        return Promise.resolve({ ok: true, json: async () => ({}) });
      }));
      render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
      await screen.findByRole("tab", { name: /alert history/i });

      fireEvent.click(screen.getByRole("tab", { name: /alert history/i }));

      expect(await screen.findByText(/water leak detected/i)).toBeTruthy();
      expect(screen.getByText("CRITICAL")).toBeTruthy();
    });

    it("Occupancy tab cannot be selected and shows the Sprint 5 not-yet-built note if reached", async () => {
      vi.stubGlobal("fetch", reportedFieldsFetch(sensors));
      render(<SensorHistoryPanel devices={sensors} apiBase="http://cabin" tempUnit="F" />);
      const occupancyTab = await screen.findByRole("tab", { name: /occupancy/i });

      fireEvent.click(occupancyTab); // disabled -- must not change the active topic

      expect(occupancyTab.getAttribute("aria-selected")).toBe("false");
      expect(screen.queryByText(/sprint 5 design work/i)).toBeNull();
    });
  });
});

describe("Monitoring reorder actually reaches the real grid (found 2026-08-25)", () => {
  afterEach(() => { cleanup(); localStorage.clear(); });

  const tempA = { deviceId: "temp-a", name: "Temp A", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin", attributes: { temperature: 20 } };
  const tempB = { deviceId: "temp-b", name: "Temp B", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin", attributes: { temperature: 22 } };
  const lock  = { deviceId: "lock-a", name: "Lock A", type: "LOCK", state: "LOCKED", location: "cabin" };

  // 2026-08-27 (user report, insurance-inspection-critical): a real, dead
  // orphaned entity (a 6-day-stale retained MQTT reading, unrelated to
  // this test's data) rendered as a normal-looking Monitoring tile
  // because this filter never checked `enabled` -- only `kpiTileFor`'s
  // type mapping gated what showed. A disabled device (or an unreviewed
  // candidate, which defaults to enabled:false) must not get a tile
  // indistinguishable from a real, in-use one.
  it("excludes a disabled device from the Monitoring grid entirely", () => {
    const disabled = { deviceId: "temp-c", name: "Temp C", type: "TEMPERATURE_SENSOR", state: "ONLINE",
      location: "cabin", attributes: { temperature: 99, enabled: false } };
    const { container } = render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, disabled]} activeLocation="cabin" active={false} reorderMode={false} />
      </AppContext.Provider>
    );
    const kpiGrid = container.querySelector(".kpi-grid");
    expect(within(kpiGrid).queryByText("Temp C")).toBeNull();
    expect(within(kpiGrid).getByText("Temp A")).toBeTruthy();
  });

  it("puts the motion sensor on the dashboard, revealing the reading only to a signed-in viewer", () => {
    const motion = { deviceId: "z2m-motion_entry", name: "motion_entry_movement_detect", type: "MOTION_SENSOR",
      state: "ONLINE", location: "cabin", attributes: { occupancy: true, battery: 100 } };
    const renderWith = (auth) => render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, motion]} activeLocation="cabin" active={false} reorderMode={false} auth={auth} />
      </AppContext.Provider>
    );

    const out = renderWith({ signedIn: false });
    const gridOut = out.container.querySelector(".kpi-grid");
    expect(within(gridOut).getByText("motion_entry_movement_detect")).toBeTruthy();
    expect(within(gridOut).getByText("100% battery")).toBeTruthy();
    expect(within(gridOut).queryByText("Motion")).toBeNull();
    cleanup();

    const inn = renderWith({ signedIn: true });
    expect(within(inn.container.querySelector(".kpi-grid")).getByText("Motion")).toBeTruthy();
  });

  it("renders tiles in the saved order, not the old fixed type-bucket order", () => {
    // Saved order deliberately puts the lock before both temp sensors --
    // the previous fixed-bucket rendering always showed every temp sensor
    // before every lock, regardless of any saved preference.
    localStorage.setItem("order.monitoring.cabin", JSON.stringify(["lock-a", "temp-b", "temp-a"]));
    const { container } = render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, tempB, lock]} activeLocation="cabin" active={false} reorderMode={false} />
      </AppContext.Provider>
    );
    // Scoped to .kpi-grid -- SensorHistoryPanel's own sensor picker also
    // renders these device names (as <option> text), unrelated to tile order.
    const kpiGrid = container.querySelector(".kpi-grid");
    const labels = within(kpiGrid).getAllByText(/^(Temp A|Temp B|Lock A)$/).map(el => el.textContent);
    expect(labels).toEqual(["Lock A", "Temp B", "Temp A"]);
  });

  it("drags the real grid tile itself, not a separate list row, and persists the new order", () => {
    const { container } = render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, tempB, lock]} activeLocation="cabin" active={false} reorderMode={true} />
      </AppContext.Provider>
    );
    const kpiGrid = container.querySelector(".kpi-grid");
    const tileA = within(kpiGrid).getByText("Temp A").closest(".kpi-tile");
    const tileLock = within(kpiGrid).getByText("Lock A").closest(".kpi-tile");
    expect(tileA.getAttribute("draggable")).toBe("true");

    // jsdom's synthetic drag events need dataTransfer supplied explicitly
    // -- a real browser always populates it, jsdom does not.
    const dataTransfer = {};
    fireEvent.dragStart(tileA, { dataTransfer });
    fireEvent.dragOver(tileLock, { dataTransfer });
    fireEvent.drop(tileLock, { dataTransfer });

    // No saved order existed beforehand, so natural array order was
    // [temp-a, temp-b, lock-a] (indices 0,1,2). Dragging temp-a (0) onto
    // lock-a (2) splices it out and reinserts at index 2 -- standard
    // move semantics, same as reorderIds elsewhere in this file -- which
    // shifts temp-b to the front, not temp-a.
    const saved = JSON.parse(localStorage.getItem("order.monitoring.cabin"));
    expect(saved).toEqual(["temp-b", "lock-a", "temp-a"]);
  });
});

// Covers Phase 7 §4c -- CameraEventsPanel's real server-side pagination/
// filtering (replacing the old client-side isCameraEvent filter + hard
// 30-event cap). See EventController's own comment and
// docs/ontology.yaml's cabin_camera_event entry.
describe("buildCameraEventsUrl", () => {
  it("requests only camera event types (DETECTION_*/MOTION_*)", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).toContain("eventTypePrefix=DETECTION_,MOTION_");
  });

  it("requests the first page at offset 0", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).toContain("offset=0");
    expect(url).toContain("limit=30");
  });

  it("requests subsequent pages at the given offset", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 30);
    expect(url).toContain("offset=30");
  });

  it("targets the given location's apiBase", () => {
    const url = buildCameraEventsUrl("http://home-hub:8080", 0);
    expect(url.startsWith("http://home-hub:8080/api/events?")).toBe(true);
  });

  // Found 2026-08-12 (user report): window=24h was hardcoded, so "Load
  // older" could never actually reach anything past 24h no matter how many
  // times clicked. window is now a real parameter, defaulting to 24h so
  // existing behavior/tests above are unchanged.
  it("defaults to a 24h window when none is given", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).toContain("window=24h");
  });

  it("uses the given window instead of the default", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0, "240h");
    expect(url).toContain("window=240h");
  });

  // 2026-08-15: a location can have real devices (AldrichFront, Home) before
  // it has its own deployed backend -- CameraEventsPanel falls back to
  // querying cabin's own apiBase filtered by this param instead. No filter
  // by default so cabin's own (already-deployed) behavior is unaffected.
  it("omits the location filter by default", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).not.toContain("location=");
  });

  it("adds a location filter when given one", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0, "24h", "home");
    expect(url).toContain("location=home");
  });
});

// 2026-08-15: FrigateEventReconciliationService/MqttBridgeService now feed
// MOTION_ON/OFF events into the same stream as real DETECTION_* activity
// -- this is CameraEventsPanel's client-side split that keeps motion from
// burying replayable detections (rendered as a separate, collapsed-by-
// default section instead of inline at the same weight).
describe("groupCameraEvents", () => {
  const detection = { eventId: "d1", eventType: "DETECTION_UPDATE" };
  const motionOn = { eventId: "m1", eventType: "MOTION_ON" };
  const motionOff = { eventId: "m2", eventType: "MOTION_OFF" };

  it("splits detections and motion events into separate buckets", () => {
    const { detections, motionEvents } = groupCameraEvents([detection, motionOn, motionOff]);
    expect(detections).toEqual([detection]);
    expect(motionEvents).toEqual([motionOn, motionOff]);
  });

  it("preserves original order within each bucket", () => {
    const detection2 = { eventId: "d2", eventType: "DETECTION_NEW" };
    const { detections } = groupCameraEvents([detection, motionOn, detection2]);
    expect(detections.map(e => e.eventId)).toEqual(["d1", "d2"]);
  });

  it("handles an empty list", () => {
    expect(groupCameraEvents([])).toEqual({ detections: [], motionEvents: [] });
  });

  it("does not throw on an entry with a missing eventType", () => {
    const { detections } = groupCameraEvents([{ eventId: "x" }]);
    expect(detections).toHaveLength(1);
  });
});

// 2026-08-15: a media 404 (clip/snapshot expired out of Frigate's shorter
// retention window, or never had footage) is expected now that the event
// list itself can reach back further than clips are ever kept -- see
// FrigateEventReconciliationService's backfillDays vs Frigate's own
// retain.days. Any other status is a real failure and should read as one.
describe("classifyMediaFetchStatus", () => {
  it("classifies 404 as missing, not an error", () => {
    expect(classifyMediaFetchStatus(404)).toBe("missing");
  });

  it("classifies every other status as a real error", () => {
    expect(classifyMediaFetchStatus(500)).toBe("error");
    expect(classifyMediaFetchStatus(401)).toBe("error");
    expect(classifyMediaFetchStatus(undefined)).toBe("error");
  });
});

describe("cameraEventsWindowLabel", () => {
  it("has a human-readable label for every offered window value", () => {
    CAMERA_EVENTS_WINDOWS.forEach(w => {
      expect(cameraEventsWindowLabel(w.value)).toBe(w.label);
    });
  });

  it("falls back to a generic label instead of crashing on an unknown value", () => {
    expect(cameraEventsWindowLabel("999h")).toBe("the selected range");
    expect(cameraEventsWindowLabel(undefined)).toBe("the selected range");
  });
});

describe("CameraEventsPanel — time range window", () => {
  afterEach(() => { cleanup(); localStorage.removeItem("cameraEvents.window"); });

  function mockAuth() {
    return {
      configured: true, signedIn: true, sessionExpired: false, userEmail: "nate@example.com",
      signOut: vi.fn(), signIn: vi.fn(), accessToken: "tok",
      authedFetch: vi.fn().mockResolvedValue({ ok: true, json: async () => [] }),
    };
  }

  function renderPanel(auth = mockAuth()) {
    return render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );
  }

  it("fetches the default 24h window on first load", async () => {
    const auth = mockAuth();
    renderPanel(auth);

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());
    expect(auth.authedFetch.mock.calls[0][0]).toContain("window=24h");
    expect(await screen.findByText(/No camera activity in last 24 hours/)).toBeTruthy();
  });

  it("refetches with the newly selected window and updates the empty-state message", async () => {
    const auth = mockAuth();
    renderPanel(auth);
    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText("Camera events time range"), { target: { value: "240h" } });

    await waitFor(() => {
      const urls = auth.authedFetch.mock.calls.map(c => c[0]);
      expect(urls.some(u => u.includes("window=240h"))).toBe(true);
    });
    expect(await screen.findByText(/No camera activity in last 10 days/)).toBeTruthy();
  });

  it("persists the selected window to localStorage and restores it on next mount", async () => {
    const auth = mockAuth();
    renderPanel(auth);
    fireEvent.change(screen.getByLabelText("Camera events time range"), { target: { value: "72h" } });
    await waitFor(() => expect(localStorage.getItem("cameraEvents.window")).toBe("72h"));
    cleanup();

    const auth2 = mockAuth();
    renderPanel(auth2);

    await waitFor(() => expect(auth2.authedFetch).toHaveBeenCalled());
    expect(auth2.authedFetch.mock.calls[0][0]).toContain("window=72h");
  });
});

// 2026-09-16 (user report): refreshCameraList() had its own
// `if (!auth.accessToken) return;` guard, written 2026-08-16 -- 19 days
// before CabinSession existed, when the raw ~1-hour Google token really
// was the only auth mechanism. When CabinSession shipped 2026-09-04 as the
// 30-day persistent login, every other data hook in this file (refresh()
// in this same component included) was already just calling authedFetch()
// directly, which prefers CabinSession automatically -- this one guard was
// never revisited. Net effect: for ~11 days, anyone relying on CabinSession
// (i.e. anyone whose accessToken had expired -- the normal state for any
// session older than an hour) silently got zero "Watch live" buttons on
// every location, with camera event history still working fine (refresh()
// has no such guard) and no error surfaced anywhere. Zero prior test
// coverage caught this -- these tests close that gap.
describe("CameraEventsPanel — live camera buttons work with CabinSession-only auth", () => {
  afterEach(cleanup);

  function mockAuthCabinSessionOnly() {
    return {
      configured: true, signedIn: true, sessionExpired: false, userEmail: "nate@example.com",
      signOut: vi.fn(), signIn: vi.fn(), accessToken: null, cabinSessionToken: "cabin-session-tok",
      authedFetch: vi.fn((url) => Promise.resolve(
        url.includes("/api/camera/list")
          ? { ok: true, json: async () => [{ name: "driveway", enabled: true }, { name: "front_door", enabled: true }] }
          : { ok: true, json: async () => [] }
      )),
    };
  }

  it("renders a Watch-live button per enabled camera when only a CabinSession is present (no accessToken)", async () => {
    const auth = mockAuthCabinSessionOnly();

    render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    expect(await screen.findByRole("button", { name: /Watch driveway live/ })).toBeTruthy();
    expect(await screen.findByRole("button", { name: /Watch front_door live/ })).toBeTruthy();
  });

  it("still requests the camera list at all -- the actual regression was this call never firing", async () => {
    const auth = mockAuthCabinSessionOnly();

    render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    await waitFor(() => {
      const urls = auth.authedFetch.mock.calls.map(c => c[0]);
      expect(urls.some(u => u.includes("/api/camera/list"))).toBe(true);
    });
  });

  // Same bug class, found in the same investigation: this effect actually
  // starts blinkbridge's on-demand liveview session server-side. Had its
  // own separate `if (!auth.accessToken) return;` guard -- so even after
  // the button-rendering fix above, clicking "Watch driveway live" would
  // show the button and open CameraLiveView's <img>, but the underlying
  // Blink liveview session would never actually start for a
  // CabinSession-only user.
  it("actually starts the liveview session when a live camera is selected, CabinSession-only", async () => {
    const auth = mockAuthCabinSessionOnly();

    render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    fireEvent.click(await screen.findByRole("button", { name: /Watch driveway live/ }));

    await waitFor(() => {
      const urls = auth.authedFetch.mock.calls.map(c => c[0]);
      expect(urls.some(u => u.includes("/api/camera/driveway/liveview/start"))).toBe(true);
    });
  });
});

// Same bug class as CameraEventsPanel above, found in the same
// investigation: OpportunityCard's logAction()/setStatus() each had their
// own `if (!auth.accessToken) return Promise.resolve();` guard -- the
// "Worth exploring"/"Not for us"/etc. buttons render fine for a
// CabinSession-only user (the surrounding render gate already correctly
// checks auth.signedIn), but clicking them silently did nothing at all.
describe("OpportunityCard — actions work with CabinSession-only auth", () => {
  afterEach(cleanup);

  function mockOpportunity() {
    return {
      id: "opp-1", findingType: "underutilized_capability", confidence: "high",
      status: "new", summary: "Test opportunity", sources: [], relatedEntityIds: [],
      actionable: null, checkedAt: new Date().toISOString(), provider: "test",
    };
  }

  function mockAuthCabinSessionOnly() {
    return {
      configured: true, signedIn: true, sessionExpired: false, userEmail: "nate@example.com",
      accessToken: null, cabinSessionToken: "cabin-session-tok",
      authedFetch: vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }),
    };
  }

  it("logs an action and updates status when 'Worth exploring' is clicked", async () => {
    const auth = mockAuthCabinSessionOnly();
    render(
      <OpportunityCard apiBase="http://cabin-hub:8090" auth={auth}
        opportunity={mockOpportunity()} entityLabels={{}} onChanged={() => {}} />
    );

    fireEvent.click(screen.getByRole("button", { name: /Worth exploring/ }));

    await waitFor(() => {
      const urls = auth.authedFetch.mock.calls.map(c => c[0]);
      expect(urls.some(u => u.includes("/api/tech-id/findings/opp-1/actions"))).toBe(true);
      expect(urls.some(u => u === "http://cabin-hub:8090/api/tech-id/findings/opp-1")).toBe(true);
    });
  });
});

// 2026-08-15: a location's devices (e.g. Home's AldrichFront, relayed
// through the cabin M920q's own blinkbridge/Frigate) can exist before that
// location has its own deployed backend. CameraEventsPanel falls back to
// cabin's own apiBase, filtered server-side, rather than hitting a
// non-existent home-hub server -- same fallback pattern RulesPanel's
// hasOwnNodeRed already uses for Node-RED embeds.
describe("CameraEventsPanel — undeployed location falls back to cabin, filtered", () => {
  afterEach(cleanup);

  function mockAuth() {
    return {
      configured: true, signedIn: true, sessionExpired: false, userEmail: "nate@example.com",
      signOut: vi.fn(), signIn: vi.fn(), accessToken: "tok",
      authedFetch: vi.fn().mockResolvedValue({ ok: true, json: async () => [] }),
    };
  }

  it("queries cabin's apiBase with a location filter when Home isn't deployed", async () => {
    const auth = mockAuth();

    render(
      <AppContext.Provider value={{ locationCfg: { id: "home", apiBase: "http://home-hub:8080" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());
    const url = auth.authedFetch.mock.calls[0][0];
    expect(url.startsWith("http://cabin-hub:8090/api/events?")).toBe(true);
    expect(url).toContain("location=home");
  });

  // 2026-08-16 (user report): this test used to assert the opposite --
  // "no location filter for cabin" -- on the theory that cabin's own
  // backend only ever holds cabin's own data. That stopped being true the
  // moment Home's AldrichFront started reconciling into the same shared
  // cabin_event table (it has no independently deployed backend of its
  // own yet), so viewing "Cabin" was silently showing Home's camera too.
  it("queries cabin's apiBase WITH a location=cabin filter, since cabin's backend can also hold another location's events", async () => {
    const auth = mockAuth();

    render(
      <AppContext.Provider value={{ locationCfg: { id: "cabin", apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());
    const url = auth.authedFetch.mock.calls[0][0];
    expect(url.startsWith("http://cabin-hub:8090/api/events?")).toBe(true);
    expect(url).toContain("location=cabin");
  });

  it("applies no location filter when viewing 'both' (locationCfg is null)", async () => {
    const auth = mockAuth();

    render(
      <AppContext.Provider value={{ locationCfg: null }}>
        <CameraEventsPanel auth={auth} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());
    const url = auth.authedFetch.mock.calls[0][0];
    expect(url).not.toContain("location=");
  });
});

// Covers Phase 7 §1b -- see docs/ontology.yaml's hub_location entity and
// mergeHubLocations' own comment in App.jsx.
describe("mergeHubLocations", () => {
  const cabin = { id: "cabin", label: "Cabin", apiBase: "http://cabin-hub:8090", grafanaUrl: "http://cabin-hub:3002" };
  const home = { id: "home", label: "Home", apiBase: "http://home-hub:8080" };
  const current = { cabin, home };

  it("adds an entirely new location from the API response", () => {
    const result = mergeHubLocations(current, [{ id: "lakehouse", label: "Lake House", apiBase: "http://lakehouse-hub:8080" }]);
    expect(Object.keys(result)).toEqual(["cabin", "home", "lakehouse"]);
    expect(result.lakehouse.label).toBe("Lake House");
  });

  it("overrides fields on an existing location the API returns a value for", () => {
    const result = mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    expect(result.cabin.label).toBe("Cabin (renamed)");
  });

  it("preserves existing fields the API response doesn't include", () => {
    const result = mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    // apiBase/grafanaUrl weren't in the API row -- must not be wiped to null
    expect(result.cabin.apiBase).toBe("http://cabin-hub:8090");
    expect(result.cabin.grafanaUrl).toBe("http://cabin-hub:3002");
  });

  it("does not mutate the object passed in as `current`", () => {
    mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    expect(current.cabin.label).toBe("Cabin"); // unchanged
  });

  it("skips rows with no id rather than throwing", () => {
    const result = mergeHubLocations(current, [{ label: "No id here" }, null, undefined]);
    expect(Object.keys(result)).toEqual(["cabin", "home"]);
  });

  it("handles an empty or missing API response", () => {
    expect(mergeHubLocations(current, [])).toEqual(current);
    expect(mergeHubLocations(current, undefined)).toEqual(current);
  });
});

// Covers the 2026-08-07 finding: the "API offline" badge required EVERY
// attempted location fetch to succeed, including home-hub's -- which is
// always going to fail until home-hub is actually deployed, permanently
// showing "offline" while viewing Home/Both regardless of cabin's real
// health. isLocationDeployed() is refreshDevices' signal for which
// locations' failures should actually count. See isLocationDeployed's
// own comment in App.jsx.
describe("isLocationDeployed", () => {
  it("treats the undeployed Docker-internal placeholder as not deployed", () => {
    expect(isLocationDeployed({ id: "home", apiBase: "http://home-hub:8080" })).toBe(false);
  });

  it("treats a real hostname/IP apiBase as deployed", () => {
    expect(isLocationDeployed({ id: "cabin", apiBase: "https://api.unicornpingpong.com" })).toBe(true);
    expect(isLocationDeployed({ id: "cabin", apiBase: "http://100.77.44.113:8090" })).toBe(true);
  });

  it("does not false-positive on a different location's placeholder-shaped host", () => {
    // "home"'s own placeholder host must not accidentally clear "cabin"'s check or vice versa
    expect(isLocationDeployed({ id: "cabin", apiBase: "http://home-hub:8080" })).toBe(true);
  });

  it("handles a missing apiBase without throwing", () => {
    expect(isLocationDeployed({ id: "home", apiBase: null })).toBe(false);
    expect(isLocationDeployed({ id: "home" })).toBe(false);
    expect(isLocationDeployed(null)).toBe(false);
  });
});

// Covers Phase 7 §3 -- place-card reordering. Renders the real
// FamilyHubPanel (not a reimplementation), reusing the same
// useDraggableOrder hook and localStorage key ("order.places") the app
// itself uses, so this exercises the actual integration, not just a
// pure helper. See docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md
// §3.
describe("FamilyHubPanel reordering", () => {
  const ORDER_KEY = "order.places";

  beforeEach(() => localStorage.removeItem(ORDER_KEY));
  afterEach(() => { cleanup(); localStorage.removeItem(ORDER_KEY); });

  function renderPanel() {
    return render(
      <ThemeProvider>
        <AppContext.Provider value={{ devices: [] }}>
          <FamilyHubPanel />
        </AppContext.Provider>
      </ThemeProvider>
    );
  }

  it("renders a card per location with no saved order", () => {
    renderPanel();
    expect(screen.getByText("Cabin")).toBeTruthy();
    expect(screen.getByText("Home")).toBeTruthy();
  });

  it("respects a previously-saved order from localStorage on initial render", () => {
    localStorage.setItem(ORDER_KEY, JSON.stringify(["home", "cabin"]));
    renderPanel();
    const labels = screen.getAllByText(/^(Cabin|Home)$/).map(el => el.textContent);
    expect(labels).toEqual(["Home", "Cabin"]);
  });

  it("toggling Reorder makes cards draggable and shows a drag handle", () => {
    const { container } = renderPanel();
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(0);

    fireEvent.click(screen.getByText("Reorder"));

    const wraps = container.querySelectorAll(".family-hub-card-wrap");
    expect(wraps.length).toBeGreaterThan(0);
    wraps.forEach(w => expect(w.getAttribute("draggable")).toBe("true"));
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(wraps.length);

    fireEvent.click(screen.getByText("Done"));
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(0);
  });

  it("a drag-and-drop persists the new order to localStorage", () => {
    const { container } = renderPanel();
    fireEvent.click(screen.getByText("Reorder"));

    const [first, second] = container.querySelectorAll(".family-hub-card-wrap");
    const dataTransfer = { effectAllowed: null };
    fireEvent.dragStart(first, { dataTransfer });
    fireEvent.dragOver(second, { dataTransfer });
    fireEvent.drop(second, { dataTransfer });

    expect(JSON.parse(localStorage.getItem(ORDER_KEY))).toEqual(["home", "cabin"]);
  });
});

// Covers the 2026-08-08 finding: hub_location's full backend CRUD
// (Phase 7 §1b) has existed since that work landed, but nothing in the
// frontend ever called POST /api/locations -- there was no way to add a
// place through the UI. User's own framing: "I will be adding another
// location" -- this needed to actually exist before that's possible.
describe("FamilyHubPanel — Add Place", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderPanel() {
    return render(
      <ThemeProvider>
        <AppContext.Provider value={{ devices: [] }}>
          <FamilyHubPanel />
        </AppContext.Provider>
      </ThemeProvider>
    );
  }

  it("Add Place reveals a form requiring at least ID and Display Name", async () => {
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));

    expect(screen.getByPlaceholderText("lakehouse")).toBeTruthy();

    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(screen.getByText(/ID and Display Name are required/)).toBeTruthy());
  });

  it("submitting a valid form POSTs only the filled-in fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);
    // onCreated() calls window.location.reload() (a real full reload is
    // the simplest way to pick up the new location -- see App.jsx's own
    // comment). jsdom logs a harmless "Not implemented: navigation"
    // stderr line for this -- it's not a failure, jsdom's Location.reload
    // isn't configurable enough in this version to stub cleanly, and the
    // assertions below don't depend on the reload actually doing anything.
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));

    fireEvent.change(screen.getByPlaceholderText("lakehouse"), { target: { value: "lakehouse" } });
    fireEvent.change(screen.getByPlaceholderText("Lake House"), { target: { value: "Lake House" } });
    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/locations$/);
    expect(opts.method).toBe("POST");
    const body = JSON.parse(opts.body);
    expect(body).toEqual({ id: "lakehouse", label: "Lake House" });
  });

  it("shows the server's error message rather than swallowing a failed create", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 400, text: async () => "id is required" }));
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));
    fireEvent.change(screen.getByPlaceholderText("lakehouse"), { target: { value: "x" } });
    fireEvent.change(screen.getByPlaceholderText("Lake House"), { target: { value: "X" } });
    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(screen.getByText("id is required")).toBeTruthy());
  });
});

describe("allLocationsLabel", () => {
  it("reads as Both for exactly two locations", () => {
    expect(allLocationsLabel(2)).toBe("Both");
  });

  it("reads as All once a third location exists", () => {
    expect(allLocationsLabel(3)).toBe("All");
    expect(allLocationsLabel(5)).toBe("All");
  });

  it("still reads as Both for a single-location instance", () => {
    expect(allLocationsLabel(1)).toBe("Both");
  });
});

// Covers the 2026-08-08 request: "offline" was firing the instant a device
// missed one poll interval, which is misleading (not-yet-reported vs.
// actually-unreachable). checkinStatusLabel is the pure function that maps
// GET /api/devices/checkin-status onto the badge shown on device cards.
describe("checkinStatusLabel", () => {
  it("never overrides an ALARM or CRITICAL state, regardless of checkin status", () => {
    expect(checkinStatusLabel("ALARM", "MISSED")).toBeNull();
    expect(checkinStatusLabel("CRITICAL", "LATE")).toBeNull();
  });

  it("shows a grace-tier label for LATE without implying the device is broken", () => {
    expect(checkinStatusLabel("OFFLINE", "LATE")).toEqual({ text: "Late checking in", cls: "state-late" });
  });

  it("shows a distinct label for MISSED", () => {
    expect(checkinStatusLabel("OFFLINE", "MISSED")).toEqual({ text: "Not responding", cls: "state-offline" });
  });

  it("shows NOT_CONFIGURED for disabled/not-yet-installed devices", () => {
    expect(checkinStatusLabel("UNKNOWN", "NOT_CONFIGURED")).toEqual({ text: "Not configured", cls: "state-not-configured" });
  });

  it("falls through to the raw state for ON_SCHEDULE or missing data", () => {
    expect(checkinStatusLabel("ONLINE", "ON_SCHEDULE")).toBeNull();
    expect(checkinStatusLabel("ONLINE", undefined)).toBeNull();
  });
});

describe("groupDevices", () => {
  const devices = [
    { deviceId: "one", type: "LOCK", state: "ONLINE", attributes: { deviceLifecycle: "ASSIGNED", discoveredFrom: "Home Assistant", room: "Entry" } },
    { deviceId: "two", type: "MOTION_SENSOR", state: "ONLINE", attributes: { deviceLifecycle: "CANDIDATE", discoveredFrom: "Zigbee2MQTT", room: "Entry" } },
  ];

  it("supports horizontal UI group dimensions without changing device order", () => {
    expect(groupDevices(devices, "room")).toEqual([["Entry", devices]]);
    expect(groupDevices(devices, "candidate").map(([name]) => name)).toEqual(["Assigned", "Candidates"]);
  });

  it("groups by workflow affiliation (alerting/automations/hvac), unmapped types fall back to Other", () => {
    const withHvac = [...devices, { deviceId: "three", type: "THERMOSTAT", state: "ONLINE", attributes: {} },
      { deviceId: "four", type: "POWER_METER", state: "ONLINE", attributes: {} }];
    const grouped = groupDevices(withHvac, "workflow");
    expect(grouped.map(([name]) => name)).toEqual(["Alerting", "Automations", "HVAC", "Other"]);
    expect(grouped.find(([name]) => name === "Alerting")[1].map(d => d.deviceId)).toEqual(["two"]);
    expect(grouped.find(([name]) => name === "Automations")[1].map(d => d.deviceId)).toEqual(["one"]);
  });

  it("WORKFLOW_BY_TYPE matches the three workflows named in the request plus a safe fallback", () => {
    expect(WORKFLOW_BY_TYPE.SMOKE_ALARM).toBe("Alerting");
    expect(WORKFLOW_BY_TYPE.THERMOSTAT).toBe("HVAC");
    expect(WORKFLOW_BY_TYPE.LOCK).toBe("Automations");
    expect(WORKFLOW_BY_TYPE.DASHBOARD).toBeUndefined(); // groupDevices falls back to "Other"
  });
});

describe("workflowsForDevice", () => {
  const leakDetected = { workflowId: "wf-leak", name: "Leak shutoff", location: "cabin", enabled: true,
    triggerDeviceId: "z2m-leak_mech_room", actions: [{ targetDeviceId: "z2m-main_water_valve" }] };
  const partyModeAlert = { workflowId: "wf-party", name: "Party mode alert", location: "cabin", enabled: true,
    triggerDeviceId: "fridge-partymode", actions: [{ targetDeviceId: "notify-nate" }] };
  const twoStepNotify = { workflowId: "wf-two-step", name: "Two-step notify", location: "cabin", enabled: false,
    triggerDeviceId: "z2m-door_front_contact",
    actions: [{ targetDeviceId: "notify-nate" }, { targetDeviceId: "z2m-leak_mech_room" }] };
  const workflows = [leakDetected, partyModeAlert, twoStepNotify];

  it("matches a device that is the trigger", () => {
    expect(workflowsForDevice(workflows, "z2m-leak_mech_room").map(w => w.workflowId))
      .toEqual(["wf-leak", "wf-two-step"]); // also matches as an action target in the second workflow
  });

  it("matches a device that is only an action target, not the trigger", () => {
    expect(workflowsForDevice(workflows, "z2m-main_water_valve").map(w => w.workflowId)).toEqual(["wf-leak"]);
  });

  it("matches a device referenced by more than one action step in the same workflow only once", () => {
    const selfReferencing = { workflowId: "wf-self", name: "Self", triggerDeviceId: "x",
      actions: [{ targetDeviceId: "x" }, { targetDeviceId: "x" }] };
    expect(workflowsForDevice([selfReferencing], "x")).toHaveLength(1);
  });

  it("returns an empty list for a device in no workflow, not a fallback category", () => {
    expect(workflowsForDevice(workflows, "z2m-motion_entry")).toEqual([]);
  });

  it("handles a missing/undefined workflows list without throwing", () => {
    expect(workflowsForDevice(undefined, "any-device")).toEqual([]);
    expect(workflowsForDevice(workflows, null)).toEqual([]);
  });
});

// 2026-08-25 (user report): a device's workflow-badge count on its row had
// no way to actually see WHICH workflows or drill into them -- neither
// DmDeviceDetail (See) nor DmEditForm (Change) rendered anything using the
// already-correct workflowsForDevice(). This is deliberately read-only
// (Fire/Activate/Delete stay on WorkflowRow in RulesPanel, tested there) --
// covers the empty state, real rows, and the "Manage in Rules & Alerts"
// jump, in both places it's rendered.
describe("DmDeviceWorkflows drill-down (See + Change)", () => {
  afterEach(cleanup);

  const device = { deviceId: "z2m-leak_mech_room", name: "Mech Room Leak", type: "WATER_LEAK_SENSOR",
    state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" } };
  const activeWorkflow = { workflowId: "wf-leak", name: "Leak shutoff", location: "cabin", enabled: true,
    triggerDeviceId: "z2m-leak_mech_room", actions: [{ targetDeviceId: "z2m-main_water_valve" }] };
  const draftWorkflow = { workflowId: "wf-draft", name: "Draft rule", location: "cabin", enabled: false,
    triggerDeviceId: "z2m-leak_mech_room", actions: [] };
  const workflows = [activeWorkflow, draftWorkflow];

  it("See mode (DmDeviceDetail): shows an honest empty state for a device in no workflow", () => {
    render(<DmDeviceDetail device={device} workflows={[]} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("Workflows (0)")).toBeTruthy();
    expect(screen.getByText("Not used by any workflow yet.")).toBeTruthy();
  });

  it("See mode (DmDeviceDetail): lists real workflow names and trigger→action summaries, active and draft both", () => {
    render(<DmDeviceDetail device={device} workflows={workflows} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("Workflows (2)")).toBeTruthy();
    expect(screen.getByText("Leak shutoff")).toBeTruthy();
    expect(screen.getByText("Draft rule")).toBeTruthy();
    expect(screen.getByText(/z2m-leak_mech_room.*z2m-main_water_valve/)).toBeTruthy();
  });

  it("See mode: 'Manage in Rules & Alerts' calls onManageWorkflows, only shown once there's something to manage", () => {
    const onManageWorkflows = vi.fn();
    const { rerender } = render(<DmDeviceDetail device={device} workflows={[]} onConfigure={() => {}}
      onLifecycleAction={vi.fn()} onManageWorkflows={onManageWorkflows} />);
    expect(screen.queryByRole("button", { name: /manage in rules/i })).toBeNull();

    rerender(<DmDeviceDetail device={device} workflows={workflows} onConfigure={() => {}}
      onLifecycleAction={vi.fn()} onManageWorkflows={onManageWorkflows} />);
    fireEvent.click(screen.getByRole("button", { name: /manage in rules/i }));
    expect(onManageWorkflows).toHaveBeenCalledOnce();
  });

  it("Change mode (DmEditForm): shows the same real workflow list", () => {
    render(<DmEditForm device={device} onSaved={() => {}} workflows={workflows} />);
    expect(screen.getByText("Workflows (2)")).toBeTruthy();
    expect(screen.getByText("Leak shutoff")).toBeTruthy();
  });

  it("Change mode (DmEditForm): empty state when the device isn't in any workflow", () => {
    render(<DmEditForm device={{ ...device, deviceId: "unrelated-device" }} onSaved={() => {}} workflows={workflows} />);
    expect(screen.getByText("Not used by any workflow yet.")).toBeTruthy();
  });
});

// 2026-08-25 (user report): "no safe one-tap action is mapped yet" gave no
// explanation of what a one-tap action is, and its "Use Change to review
// its configuration" instruction wasn't a link -- a dead end. Now honest
// about what the preset actually covers, with a real button that reuses
// the See→Change onConfigure path (so the same device stays selected,
// per the DeviceManagerPanel fix below).
describe("DmCapabilityActions — one-tap action hint", () => {
  afterEach(cleanup);

  const outOfPresetDevice = {
    deviceId: "climate-1", name: "Thermostat", type: "THERMOSTAT", state: "ONLINE", location: "cabin",
    attributes: { deviceLifecycle: "ASSIGNED", capabilities: ["COMMAND"], entityId: "climate.thermostat" },
  };

  it("explains the preset instead of unexplained 'one-tap action' jargon, and offers a real way into Change", () => {
    const onConfigure = vi.fn();
    render(<DmDeviceDetail device={outOfPresetDevice} onConfigure={onConfigure} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText(/safe one-tap buttons for a small preset of actions/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /review its configuration in change/i }));
    expect(onConfigure).toHaveBeenCalledOnce();
  });

  it("still offers real one-tap buttons for a whitelisted domain (switch/light/cover) -- unchanged", () => {
    const switchDevice = { ...outOfPresetDevice, deviceId: "switch-1", type: "GOOGLE_HOME_DEVICE",
      attributes: { ...outOfPresetDevice.attributes, entityId: "switch.porch" } };
    render(<DmDeviceDetail device={switchDevice} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Turn on" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Turn off" })).toBeTruthy();
    expect(screen.queryByText(/safe one-tap buttons for a small preset/i)).toBeNull();
  });
});

describe("Device Manager lifecycle visibility", () => {
  const assigned = { deviceId: "assigned", attributes: { deviceLifecycle: "ASSIGNED" } };
  const available = { deviceId: "available", attributes: { deviceLifecycle: "AVAILABLE" } };
  const candidate = { deviceId: "candidate", attributes: { deviceLifecycle: "CANDIDATE" } };
  const deferred = { deviceId: "deferred", attributes: { deviceLifecycle: "DEFERRED" } };
  const ignored = { deviceId: "ignored", attributes: { deviceLifecycle: "IGNORED" } };
  const legacyConfigured = { deviceId: "legacy", attributes: {} };
  const devices = [assigned, available, candidate, deferred, ignored, legacyConfigured];

  // 2026-08-25: the default used to exclude candidates -- but every OTHER
  // option in this dropdown narrows the view, so a default that silently
  // hid undecided candidates wasn't actually "everything you should see
  // by default." Merged the old separate "all" filter into this same
  // default (relabeled "All In-Scope + Candidates" in the dropdown).
  it("shows in-scope devices AND candidates in the default view -- deferred/ignored still excluded", () => {
    expect(filterDeviceManagerDevices(devices).map(d => d.deviceId))
      .toEqual(["assigned", "available", "candidate", "legacy"]);
  });

  it("shows candidates only when Candidates is the only State checked", () => {
    expect(filterDeviceManagerDevices(devices, { lifecycle: ["CANDIDATE"] }).map(d => d.deviceId)).toEqual(["candidate"]);
  });

  it("shows deferred/ignored devices when their State boxes are checked -- the 'Review previously exposed' mode's own filter shape", () => {
    expect(filterDeviceManagerDevices(devices, { lifecycle: ["DEFERRED", "IGNORED"] }).map(d => d.deviceId))
      .toEqual(["deferred", "ignored"]);
  });

  // 2026-09-16: Parent-only and State used to be one mutually-exclusive
  // enum (parents_only/candidates/previous/in_scope) -- a real, reasonable
  // combination like "parent devices only, but not Candidate or Assigned"
  // simply couldn't be expressed. Now they're independent facets on the
  // same call.
  it("combines Parent-only with an arbitrary State selection", () => {
    expect(filterDeviceManagerDevices(devices, { parentOnly: true, lifecycle: ["AVAILABLE", "DEFERRED"] }).map(d => d.deviceId))
      .toEqual(["available", "deferred"]);
  });

  // 2026-08-27 (user report): grouping by Type produced one enormous
  // catch-all group (every un-typed HA sub-entity/service, 100+ rows on
  // the real cabin instance) dwarfing every other group -- reusing the
  // same parentDeviceId relationship the toolbar's device-count toggle
  // already reads (countParentDevices) as a real list filter so a person
  // can actually collapse down to just the physical devices.
  it("shows only devices without a parentDeviceId when Parent devices only is on", () => {
    const child = { deviceId: "child", attributes: { deviceLifecycle: "ASSIGNED", parentDeviceId: "assigned" } };
    const withChild = [...devices, child];
    expect(filterDeviceManagerDevices(withChild, { parentOnly: true }).map(d => d.deviceId))
      .toEqual(["assigned", "available", "candidate", "legacy"]);
  });

  it("Parent devices only still excludes deferred/ignored devices by default, same as the default view", () => {
    expect(filterDeviceManagerDevices(devices, { parentOnly: true }).map(d => d.deviceId))
      .toEqual(["assigned", "available", "candidate", "legacy"]);
  });

  it("always reconciles Lifecycle grouping to every state, Parent-only ignored", () => {
    expect(resolveDeviceManagerFilter("candidate", { parentOnly: true, lifecycle: ["CANDIDATE"] }))
      .toEqual({ parentOnly: false, lifecycle: LIFECYCLE_FILTER_OPTIONS.map(o => o.value) });
    const saved = { parentOnly: true, lifecycle: ["CANDIDATE"] };
    expect(resolveDeviceManagerFilter("workflow", saved)).toBe(saved);
  });

  it("derives legacy candidate booleans but prefers the lifecycle enum", () => {
    expect(deviceLifecycleState({ attributes: { candidate: true } })).toBe("CANDIDATE");
    expect(deviceLifecycleState({ attributes: { candidate: true, deviceLifecycle: "available" } })).toBe("AVAILABLE");
    expect(deviceLifecycleState({ attributes: {} })).toBe("ASSIGNED");
  });
});

// 2026-08-25: the toolbar's "157 devices" counted every HA sub-entity as
// its own device (Kidde's ~9-18 entities, Liebherr's 9, etc.) with no way
// to tell how many physical things that represents. countParentDevices
// is the "parent devices only" half of the new toggle.
describe("countParentDevices", () => {
  it("counts every device when none has a parent set (today's real-world starting state)", () => {
    const devices = [
      { deviceId: "a", attributes: {} },
      { deviceId: "b", attributes: {} },
      { deviceId: "c", attributes: {} },
    ];
    expect(countParentDevices(devices)).toBe(3);
  });

  it("excludes devices that have a parentDeviceId set -- they're a service of something else", () => {
    const devices = [
      { deviceId: "kidde-unit", attributes: {} },
      { deviceId: "kidde-co-alarm", attributes: { parentDeviceId: "kidde-unit" } },
      { deviceId: "kidde-humidity", attributes: { parentDeviceId: "kidde-unit" } },
      { deviceId: "standalone-lock", attributes: {} },
    ];
    expect(countParentDevices(devices)).toBe(2); // kidde-unit + standalone-lock
  });

  it("treats a blank parentDeviceId (cleared, not unset) the same as no parent", () => {
    const devices = [{ deviceId: "a", attributes: { parentDeviceId: "" } }];
    expect(countParentDevices(devices)).toBe(1);
  });
});

describe("DmDeviceDetail ontology metadata (category/capabilities)", () => {
  afterEach(cleanup);

  const baseDevice = {
    deviceId: "cam-1", name: "Driveway", type: "CAMERA", state: "ONLINE", location: "cabin",
    attributes: { deviceLifecycle: "ASSIGNED", category: "SECURITY", capabilities: ["STREAM", "TELEMETRY"] },
  };

  it("shows the real backend category as a badge, not a client-side WORKFLOW_BY_TYPE guess", () => {
    render(<DmDeviceDetail device={baseDevice} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("SECURITY")).toBeTruthy();
  });

  it("shows each real capability as its own chip", () => {
    render(<DmDeviceDetail device={baseDevice} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("STREAM")).toBeTruthy();
    expect(screen.getByText("TELEMETRY")).toBeTruthy();
  });

  it("does not duplicate category/capabilities in the generic Attributes dump below", () => {
    render(<DmDeviceDetail device={baseDevice} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.queryByText("category")).toBeNull();
    expect(screen.queryByText("capabilities")).toBeNull();
  });

  it("renders cleanly when category/capabilities are absent (older cached device, or a fetch that predates this)", () => {
    const device = { deviceId: "old-1", name: "Legacy", type: "SENSOR", state: "ONLINE", location: "cabin",
      attributes: { deviceLifecycle: "ASSIGNED" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.queryByText(/category/i)).toBeNull();
    expect(screen.queryByText(/capabilities/i)).toBeNull();
  });
});

// 2026-08-25, Item 4a: parentDeviceId's resolved-name display -- same
// "show the real thing, not a raw id" reasoning as category/capabilities
// above.
describe("DmDeviceDetail parent device (Item 4a)", () => {
  afterEach(cleanup);

  const child = { deviceId: "kidde-co", name: "CO Alarm", type: "CO_ALARM", state: "ONLINE", location: "cabin",
    attributes: { deviceLifecycle: "ASSIGNED", parentDeviceId: "kidde-unit" } };

  it("resolves and shows the parent device's real name via AppContext, not the raw id", () => {
    render(
      <AppContext.Provider value={{ devices: [{ deviceId: "kidde-unit", name: "Kidde CO/Air Quality Unit" }] }}>
        <DmDeviceDetail device={child} onConfigure={() => {}} onLifecycleAction={vi.fn()} />
      </AppContext.Provider>
    );
    expect(screen.getByText("Kidde CO/Air Quality Unit")).toBeTruthy();
    expect(screen.queryByText("kidde-unit")).toBeNull();
  });

  it("falls back to the raw id if the parent isn't in the current device list", () => {
    render(
      <AppContext.Provider value={{ devices: [] }}>
        <DmDeviceDetail device={child} onConfigure={() => {}} onLifecycleAction={vi.fn()} />
      </AppContext.Provider>
    );
    expect(screen.getByText("kidde-unit")).toBeTruthy();
  });

  it("does not crash with no AppContext.Provider at all (matches every other DmDeviceDetail test's render style)", () => {
    render(<DmDeviceDetail device={child} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("kidde-unit")).toBeTruthy();
  });

  it("shows no Belongs to row and no raw parentDeviceId in the Attributes dump when unset", () => {
    const standalone = { deviceId: "driveway", name: "Driveway Cam", type: "CAMERA", state: "ONLINE", location: "cabin",
      attributes: { deviceLifecycle: "ASSIGNED" } };
    render(<DmDeviceDetail device={standalone} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.queryByText("Belongs to")).toBeNull();
    expect(screen.queryByText("parentDeviceId")).toBeNull();
  });
});

// 2026-08-27: reportsFields (DeviceType.telemetryFields(), serialized by
// DeviceRegistry.withOntologyMetadata()) closes the gap the user hit --
// a Zigbee combo sensor types as TEMPERATURE_SENSOR alone with no visible
// sign it also reports humidity, so a person browsing Device Manager had
// to already know that to make sense of it. Same "show the real thing,
// don't make a human infer it" reasoning as Capabilities/Belongs to.
describe("DmDeviceDetail reports fields", () => {
  afterEach(cleanup);

  it("shows a Reports row with human labels for a combo sensor's reportsFields", () => {
    const combo = { deviceId: "z2m-temp_kitchen", name: "Kitchen", type: "TEMPERATURE_SENSOR",
      state: "ONLINE", location: "cabin",
      attributes: { deviceLifecycle: "ASSIGNED", reportsFields: ["humidity", "temperature"] } };
    render(<DmDeviceDetail device={combo} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.getByText("Reports")).toBeTruthy();
    expect(screen.getByText("Humidity")).toBeTruthy();
    expect(screen.getByText("Temperature")).toBeTruthy();
  });

  it("shows no Reports row when reportsFields is empty", () => {
    const camera = { deviceId: "driveway-2", name: "Driveway Cam", type: "CAMERA",
      state: "ONLINE", location: "cabin",
      attributes: { deviceLifecycle: "ASSIGNED", reportsFields: [] } };
    render(<DmDeviceDetail device={camera} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);
    expect(screen.queryByText("Reports")).toBeNull();
  });
});

// Part D (2026-09-02): the "Previously exposed" review view had no "since
// when" marker on a DEFERRED/IGNORED device -- lifecycleUpdatedAt (backed
// by the device table's own updated_at column, see DeviceLifecycleRecord's
// doc) closes that gap.
describe("DmDeviceDetail previously-exposed 'since' timestamp (Part D)", () => {
  afterEach(cleanup);

  it("shows 'Ignored since' with the device's lifecycleUpdatedAt for an IGNORED device", () => {
    const device = { deviceId: "old-sensor", name: "Old sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin",
      attributes: { deviceLifecycle: "IGNORED", lifecycleUpdatedAt: "2026-08-20T12:00:00Z" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);

    expect(screen.getByText(/^Ignored since /)).toBeTruthy();
  });

  it("shows 'Deferred since', not 'Ignored since', for a DEFERRED device", () => {
    const device = { deviceId: "old-sensor", name: "Old sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin",
      attributes: { deviceLifecycle: "DEFERRED", lifecycleUpdatedAt: "2026-08-20T12:00:00Z" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);

    expect(screen.getByText(/^Deferred since /)).toBeTruthy();
    expect(screen.queryByText(/^Ignored since /)).toBeNull();
  });

  it("omits the since line gracefully when lifecycleUpdatedAt is missing", () => {
    const device = { deviceId: "old-sensor", name: "Old sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin",
      attributes: { deviceLifecycle: "IGNORED" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);

    expect(screen.getByText("Previously exposed device")).toBeTruthy();
    expect(screen.queryByText(/since /)).toBeNull();
  });

  it("does not duplicate lifecycleUpdatedAt as a raw attribute row", () => {
    const device = { deviceId: "old-sensor", name: "Old sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin",
      attributes: { deviceLifecycle: "IGNORED", lifecycleUpdatedAt: "2026-08-20T12:00:00Z" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} />);

    expect(screen.queryByText("lifecycleUpdatedAt")).toBeNull();
  });
});

// Confirmed 2026-09-02: "Remove" calls the same non-destructive IGNORE
// lifecycle action the previously-exposed review screen uses -- it used to
// be a real DELETE FROM device (see DeviceController.removeDevice()'s own
// comment). These pin the corrected copy so a future edit can't silently
// reintroduce "cannot be undone"/"disappear" language that's no longer true.
describe("DmRemoveView (non-destructive Remove, 2026-09-02)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const device = { deviceId: "z2m-old-sensor", name: "Old Sensor", location: "cabin" };

  it("the pre-confirm panel says data is retained, not that this is destructive", () => {
    render(<DmRemoveView devices={[device]} selected="z2m-old-sensor" onSelect={() => {}} onRefresh={() => {}} />);

    expect(screen.getByText(/keeps its data/i)).toBeTruthy();
    expect(screen.getByText(/previously exposed/i)).toBeTruthy();
    expect(screen.queryByText(/cannot be undone/i)).toBeNull();
  });

  it("the confirmation step also says it's reversible, not that it disappears permanently", () => {
    render(<DmRemoveView devices={[device]} selected="z2m-old-sensor" onSelect={() => {}} onRefresh={() => {}} />);

    fireEvent.click(screen.getByText("Remove this device"));

    expect(screen.getByText(/nothing is deleted/i)).toBeTruthy();
    expect(screen.getByText(/restore it later/i)).toBeTruthy();
    expect(screen.queryByText(/cannot be undone/i)).toBeNull();
  });

  it("confirming still calls DELETE on the device endpoint (server-side now does IGNORE, not a real delete)", async () => {
    const authedFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    const onRefresh = vi.fn();
    render(<DmRemoveView devices={[device]} selected="z2m-old-sensor" onSelect={() => {}} onRefresh={onRefresh}
      auth={{ authedFetch }} />);
    fireEvent.click(screen.getByText("Remove this device"));

    fireEvent.click(screen.getByText("Confirm remove"));

    await waitFor(() => expect(authedFetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/devices/z2m-old-sensor"), expect.objectContaining({ method: "DELETE" })));
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });
});

describe("Device candidate decision controls", () => {
  afterEach(cleanup);

  it("does not change lifecycle when details are merely reviewed", () => {
    const onConfigure = vi.fn();
    const onLifecycleAction = vi.fn();
    render(<DmDeviceDetail
      device={{ deviceId: "candidate", name: "New sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" } }}
      onConfigure={onConfigure}
      onLifecycleAction={onLifecycleAction}
    />);

    fireEvent.click(screen.getByRole("button", { name: /review details without deciding/i }));
    expect(onConfigure).toHaveBeenCalledOnce();
    expect(onLifecycleAction).not.toHaveBeenCalled();
  });

  it("sends an explicit ACCEPT decision when Use this device is chosen", async () => {
    const onLifecycleAction = vi.fn().mockResolvedValue({ deviceLifecycle: "AVAILABLE" });
    render(<DmDeviceDetail
      device={{ deviceId: "candidate", name: "New sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" } }}
      onConfigure={() => {}}
      onLifecycleAction={onLifecycleAction}
    />);

    fireEvent.click(screen.getByRole("button", { name: /use this device/i }));
    await waitFor(() => expect(onLifecycleAction).toHaveBeenCalledWith(expect.objectContaining({ deviceId: "candidate" }), "ACCEPT"));
    expect(await screen.findByText("Decision saved.")).toBeTruthy();
  });

  it("offers to recognize a candidate and hands off to onOpenDiscovery", () => {
    const onOpenDiscovery = vi.fn();
    const device = { deviceId: "candidate", name: "New sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} onOpenDiscovery={onOpenDiscovery} />);

    fireEvent.click(screen.getByRole("button", { name: /recognize this device/i }));
    expect(onOpenDiscovery).toHaveBeenCalledWith(device, "new");
  });

  it("shows the first-seen nudge and a primary-styled button when discoverySuggested is set", () => {
    const device = { deviceId: "candidate", name: "New sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE", discoverySuggested: true } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} onOpenDiscovery={() => {}} />);

    expect(screen.getByText(/new device.*want to look it up/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /recognize this device/i }).className).toContain("btn-primary");
  });

  it("does not show the first-seen nudge for a candidate that isn't newly discovered", () => {
    const device = { deviceId: "candidate", name: "New sensor", type: "MOTION_SENSOR", state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" } };
    render(<DmDeviceDetail device={device} onConfigure={() => {}} onLifecycleAction={vi.fn()} onOpenDiscovery={() => {}} />);

    expect(screen.queryByText(/want to look it up/i)).toBeNull();
    expect(screen.getByRole("button", { name: /recognize this device/i }).className).toContain("btn-secondary");
  });
});

describe("Device candidate configuration", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("allows Enabled on the first edit and persists the explicit assignment decision", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ changed: true, enabled: true, deviceLifecycle: "ASSIGNED" }),
    }));
    const onSaved = vi.fn();
    render(<DmEditForm
      device={{
        deviceId: "candidate-first-save",
        name: "Basement leak sensor",
        type: "WATER_LEAK_SENSOR",
        state: "ONLINE",
        location: "cabin",
        attributes: { deviceLifecycle: "CANDIDATE", enabled: false },
      }}
      onSaved={onSaved}
    />);

    const enabledToggle = screen.getByTitle(/saving enabled on accepts and assigns/i);
    expect(enabledToggle.disabled).toBe(false);
    fireEvent.click(enabledToggle);
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    const [, options] = fetch.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ name: "Basement leak sensor", enabled: true, room: "", parentDeviceId: "" });
  });

  // 2026-08-25: a candidate reached via DmChangeView (which renders
  // DmEditForm directly, unlike DmSeeView's DmDeviceDetail) used to have
  // no discovery/lookup option at all -- the button was unconditionally
  // hidden for CANDIDATE lifecycle. Fixed to stay visible, using mode
  // "new" (the backend-correct path for a candidate) instead of "replace"
  // (which DeviceRegistry.replaceConfiguration() itself rejects for a
  // still-undecided candidate).
  it("offers a lookup for a candidate device too, using mode=new not replace", () => {
    const onOpenDiscovery = vi.fn();
    const device = {
      deviceId: "candidate-lookup", name: "Unknown sensor", type: "MOTION_SENSOR",
      state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" },
    };
    render(<DmEditForm device={device} onSaved={() => {}} onOpenDiscovery={onOpenDiscovery} />);

    const button = screen.getByRole("button", { name: /recognize this device/i });
    fireEvent.click(button);
    expect(onOpenDiscovery).toHaveBeenCalledWith(device, "new");
  });

  it("still offers Re-check device info (mode=replace) for an already-assigned device", () => {
    const onOpenDiscovery = vi.fn();
    const device = {
      deviceId: "assigned-recheck", name: "Kitchen sensor", type: "TEMPERATURE_SENSOR",
      state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED", enabled: true },
    };
    render(<DmEditForm device={device} onSaved={() => {}} onOpenDiscovery={onOpenDiscovery} />);

    fireEvent.click(screen.getByRole("button", { name: /re-check device info/i }));
    expect(onOpenDiscovery).toHaveBeenCalledWith(device, "replace");
  });

  it("prefills Room from the device's existing attribute and includes it unchanged when saving something else", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ changed: true, enabled: false, deviceLifecycle: "ASSIGNED" }),
    }));
    const onSaved = vi.fn();
    render(<DmEditForm
      device={{
        deviceId: "fridge-partymode", name: "Party Mode", type: "HOME_ASSISTANT_ENTITY",
        state: "ONLINE", location: "cabin",
        attributes: { deviceLifecycle: "ASSIGNED", enabled: false, room: "Kitchen" },
      }}
      onSaved={onSaved}
    />);

    expect(screen.getByLabelText(/room/i).value).toBe("Kitchen");
    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: "Party Mode Switch" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    const [, options] = fetch.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ name: "Party Mode Switch", enabled: false, room: "Kitchen", parentDeviceId: "" });
  });

  it("saving a room-only edit is enabled and sends the new room", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ changed: true, enabled: false, deviceLifecycle: "ASSIGNED" }),
    }));
    render(<DmEditForm
      device={{
        deviceId: "kidde-co", name: "Kidde CO Alarm", type: "CO_ALARM",
        state: "ONLINE", location: "cabin",
        attributes: { deviceLifecycle: "ASSIGNED", enabled: true, room: "" },
      }}
      onSaved={() => {}}
    />);

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    expect(saveButton.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/room/i), { target: { value: "Mechanical Room" } });
    expect(saveButton.disabled).toBe(false);
    fireEvent.click(saveButton);

    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const [, options] = fetch.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ name: "Kidde CO Alarm", enabled: true, room: "Mechanical Room", parentDeviceId: "" });
  });
});

// D15/Sprint 5 Area pipe (ratified 2026-09-05): its own small, atomic save
// against PATCH /api/devices/{id}/area -- a separate backend write path
// (DeviceMetadata.area, a real column) from Room's bundled PATCH .../config,
// so it necessarily has its own Save button rather than riding the main
// form's "Save changes".
describe("DmAreaEditor (Area pipe, via DmEditForm)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const device = {
    deviceId: "z2m-motion_entry", name: "motion_entry", type: "MOTION_SENSOR",
    state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED", enabled: true },
  };

  it("Area input starts empty and Save is disabled until a real value is entered", () => {
    render(<DmEditForm device={device} onSaved={() => {}} />);

    const areaInput = screen.getByLabelText(/^area$/i);
    expect(areaInput.value).toBe("");
    expect(screen.getByRole("button", { name: /^save$/i }).disabled).toBe(true);
  });

  it("prefills Area from the device's existing attribute, and Save stays disabled until it actually changes", () => {
    render(<DmEditForm device={{ ...device, attributes: { ...device.attributes, area: "Entryway" } }} onSaved={() => {}} />);

    expect(screen.getByLabelText(/^area$/i).value).toBe("Entryway");
    expect(screen.getByRole("button", { name: /^save$/i }).disabled).toBe(true);
  });

  it("saving a new Area calls the dedicated area endpoint, not the main config PATCH", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ deviceId: device.deviceId, area: "Entryway" }) }));
    const onSaved = vi.fn();
    render(<DmEditForm device={device} onSaved={onSaved} />);

    fireEvent.change(screen.getByLabelText(/^area$/i), { target: { value: "Entryway" } });
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    const [url, options] = fetch.mock.calls[0];
    expect(url).toContain(`/api/devices/${device.deviceId}/area`);
    expect(options.method).toBe("PATCH");
    expect(JSON.parse(options.body)).toEqual({ area: "Entryway" });
    expect(await screen.findByText(/saved/i)).toBeTruthy();
  });

  it("shows a real error instead of a silent failure when the area save fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 400, json: async () => ({ error: "area must not be blank" }),
    }));
    render(<DmEditForm device={device} onSaved={() => {}} />);

    fireEvent.change(screen.getByLabelText(/^area$/i), { target: { value: "Somewhere" } });
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/not saved: area must not be blank/i)).toBeTruthy();
  });
});

describe("DmDeviceRow area display", () => {
  afterEach(() => cleanup());

  it("shows the Area as a prefix in the row's meta line when set", () => {
    render(<DmDeviceRow device={{
      deviceId: "z2m-motion_entry", name: "motion_entry", type: "MOTION_SENSOR",
      state: "ONLINE", location: "cabin", attributes: { area: "Entryway" },
    }} onClick={() => {}} />);

    expect(screen.getByText("Entryway")).toBeTruthy();
  });

  it("renders no area tag at all when unset, rather than a blank one", () => {
    const { container } = render(<DmDeviceRow device={{
      deviceId: "z2m-motion_entry", name: "motion_entry", type: "MOTION_SENSOR",
      state: "ONLINE", location: "cabin", attributes: {},
    }} onClick={() => {}} />);

    expect(container.querySelector(".dm-row-area")).toBeNull();
  });
});

// 2026-08-25, Item 4a: the Parent device picker itself. Server-side
// validation (self/nonexistent/cross-location/cycle) is DeviceRegistryTest's
// job -- this covers only what the picker offers and what it saves.
describe("DmEditForm parent device picker (Item 4a)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const child = {
    deviceId: "kidde-co", name: "CO Alarm", type: "CO_ALARM", state: "ONLINE", location: "cabin",
    attributes: { deviceLifecycle: "ASSIGNED", enabled: true, room: "" },
  };
  const devices = [
    child,
    { deviceId: "kidde-unit", name: "Kidde CO/Air Quality Unit", location: "cabin" },
    { deviceId: "home-thermostat", name: "Home Thermostat", location: "home" },
  ];

  it("offers only same-location devices, excluding itself, as parent candidates", () => {
    render(
      <AppContext.Provider value={{ devices }}>
        <DmEditForm device={child} onSaved={() => {}} />
      </AppContext.Provider>
    );
    const select = screen.getByLabelText(/parent device/i);
    const optionLabels = [...select.querySelectorAll("option")].map(o => o.textContent);
    expect(optionLabels).toContain("Kidde CO/Air Quality Unit");
    expect(optionLabels).not.toContain("Home Thermostat");
    expect(optionLabels).not.toContain("CO Alarm");
  });

  it("saves the selected parent device id", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ changed: true, enabled: true, deviceLifecycle: "ASSIGNED" }),
    }));
    render(
      <AppContext.Provider value={{ devices }}>
        <DmEditForm device={child} onSaved={() => {}} />
      </AppContext.Provider>
    );

    fireEvent.change(screen.getByLabelText(/parent device/i), { target: { value: "kidde-unit" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const [, options] = fetch.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ name: "CO Alarm", enabled: true, room: "", parentDeviceId: "kidde-unit" });
  });

  it("surfaces a server-side rejection (e.g. a cycle) as a real error, not a silent no-op", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ error: "Setting this parent would create a cycle" }),
    }));
    render(
      <AppContext.Provider value={{ devices }}>
        <DmEditForm device={child} onSaved={() => {}} />
      </AppContext.Provider>
    );

    fireEvent.change(screen.getByLabelText(/parent device/i), { target: { value: "kidde-unit" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expect(await screen.findByText(/would create a cycle/i)).toBeTruthy();
  });

  it("renders cleanly with no AppContext.Provider (matches this file's existing DmEditForm render style)", () => {
    render(<DmEditForm device={child} onSaved={() => {}} />);
    expect(screen.getByLabelText(/parent device/i)).toBeTruthy();
  });
});

describe("DmDeviceRow inline enable toggle", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const enabledDevice = {
    deviceId: "z2m-entry", name: "Entry motion", type: "MOTION_SENSOR",
    state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED", enabled: true },
  };

  it("does not render the toggle when the caller doesn't pass onToggled (DmRemoveView, MnChangeView)", () => {
    render(<DmDeviceRow device={enabledDevice} onClick={() => {}} />);
    expect(screen.queryByTitle(/disable|enable/i)).toBeNull();
  });

  it("clicking the toggle flips enabled and calls onToggled on success, without also triggering row selection", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ changed: true, enabled: false, deviceLifecycle: "ASSIGNED" }),
    }));
    const onClick = vi.fn();
    const onToggled = vi.fn();
    render(<DmDeviceRow device={enabledDevice} onClick={onClick} onToggled={onToggled} />);

    fireEvent.click(screen.getByTitle("Disable"));

    await waitFor(() => expect(onToggled).toHaveBeenCalledOnce());
    const [url, options] = fetch.mock.calls[0];
    expect(url).toContain("/api/devices/z2m-entry/config");
    expect(JSON.parse(options.body)).toEqual({ enabled: false });
    expect(onClick).not.toHaveBeenCalled();
  });

  it("a failed toggle surfaces the error without calling onToggled", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 500, json: async () => ({}),
    }));
    const onToggled = vi.fn();
    render(<DmDeviceRow device={enabledDevice} onClick={() => {}} onToggled={onToggled} />);

    fireEvent.click(screen.getByTitle("Disable"));

    await waitFor(() => expect(screen.getByTitle(/not saved/i)).toBeTruthy());
    expect(onToggled).not.toHaveBeenCalled();
  });

  it("a still-CANDIDATE device's toggle explains that enabling accepts and assigns it", () => {
    const candidate = {
      deviceId: "candidate-1", name: "New sensor", type: "CONTACT_SENSOR",
      state: "UNKNOWN", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE", enabled: false },
    };
    render(<DmDeviceRow device={candidate} onClick={() => {}} onToggled={() => {}} />);
    expect(screen.getByTitle(/enabling accepts and assigns/i)).toBeTruthy();
  });
});

describe("DmDeviceRow workflow badge", () => {
  afterEach(cleanup);

  const device = {
    deviceId: "z2m-leak_mech_room", name: "Mech room leak sensor", type: "WATER_LEAK_SENSOR",
    state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" },
  };
  const workflows = [
    { workflowId: "wf-1", name: "Leak shutoff", triggerDeviceId: "z2m-leak_mech_room", actions: [] },
    { workflowId: "wf-2", name: "Leak notify", triggerDeviceId: "z2m-leak_mech_room", actions: [] },
  ];

  it("shows no badge when workflows isn't passed at all (DmRemoveView, MnChangeView)", () => {
    render(<DmDeviceRow device={device} onClick={() => {}} />);
    expect(screen.queryByText(/workflow/i)).toBeNull();
  });

  it("shows no badge when workflows is passed but this device isn't in any", () => {
    render(<DmDeviceRow device={device} onClick={() => {}} workflows={[]} />);
    expect(screen.queryByText(/workflow/i)).toBeNull();
  });

  it("shows a count badge with the workflow names in its title when the device is in one or more workflows", () => {
    render(<DmDeviceRow device={device} onClick={() => {}} workflows={workflows} />);
    expect(screen.getByText("2 workflows")).toBeTruthy();
    expect(screen.getByText("2 workflows").title).toBe("Leak shutoff, Leak notify");
  });

  it("singular wording for exactly one workflow", () => {
    render(<DmDeviceRow device={device} onClick={() => {}} workflows={[workflows[0]]} />);
    expect(screen.getByText("1 workflow")).toBeTruthy();
  });
});

// The lifecycle badge's label source -- GET /api/devices/meta/lifecycle,
// fetched once in App() and threaded via AppContext (see
// useLifecycleStateLabels). No Provider at all must still work (existing
// tests above render DmDeviceRow bare); a Provider with real fetched
// labels must actually override the hardcoded LIFECYCLE_LABELS fallback.
describe("DmDeviceRow lifecycle badge label source", () => {
  afterEach(cleanup);

  const candidateDevice = {
    deviceId: "z2m-new_sensor", name: "New sensor", type: "MOTION_SENSOR",
    state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "CANDIDATE" },
  };

  it("falls back to the hardcoded label when rendered with no AppContext at all", () => {
    render(<DmDeviceRow device={candidateDevice} onClick={() => {}} />);
    expect(screen.getByText("Candidates")).toBeTruthy();
  });

  it("uses the fetched label from AppContext when one is provided, not the hardcoded default", () => {
    render(
      <AppContext.Provider value={{ lifecycleLabels: { CANDIDATE: "Newly found" } }}>
        <DmDeviceRow device={candidateDevice} onClick={() => {}} />
      </AppContext.Provider>
    );
    expect(screen.getByText("Newly found")).toBeTruthy();
    expect(screen.queryByText("Candidates")).toBeNull();
  });
});

describe("WorkflowRulesCard", () => {
  // 2026-08-21: WorkflowRulesCard now renders RecentExecutionsList, which
  // fetches GET .../api/rules/executions/recent on mount (see App.jsx) --
  // every test in this block needs a default fetch stub now, not just the
  // ones that explicitly exercise history/recent, or it would otherwise
  // hit real (unresolvable "http://cabin"/"http://home") network calls in
  // an unmocked jsdom+Node fetch environment. Individual tests below
  // override this default where they need specific recent-executions data.
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows an empty state when there are no workflows", () => {
    render(<WorkflowRulesCard workflows={[]} />);
    expect(screen.getByText(/no workflows configured yet/i)).toBeTruthy();
  });

  it("shows each workflow's name, trigger, actions, and active/draft status", () => {
    render(<WorkflowRulesCard workflows={[
      { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true,
        triggerDeviceId: "z2m-leak_mech_room", actions: [{ targetDeviceId: "z2m-main_water_valve" }] },
      { workflowId: "wf-2", name: "Draft alert", location: "cabin", enabled: false,
        triggerDeviceId: "z2m-door_front_contact", actions: [] },
    ]} />);

    expect(screen.getByText("Leak shutoff")).toBeTruthy();
    expect(screen.getByText(/z2m-leak_mech_room.*z2m-main_water_valve/)).toBeTruthy();
    // Location and status are separate layer-3 chips now (was one joined string).
    expect(screen.getByText("active")).toBeTruthy();

    expect(screen.getByText("Draft alert")).toBeTruthy();
    expect(screen.getByText(/no actions/i)).toBeTruthy();
    expect(screen.getByText("draft")).toBeTruthy();
    expect(screen.getAllByText("cabin")).toHaveLength(2);
  });

  it("prompts to sign in instead of offering workflow creation when not signed in", () => {
    render(<WorkflowRulesCard workflows={[]} auth={{ signedIn: false, signIn: vi.fn() }} />);
    expect(screen.getByRole("button", { name: /sign in with google/i })).toBeTruthy();
    expect(screen.queryByText("+ New Workflow")).toBeNull();
  });

  function mockAuth() {
    return {
      configured: true, signedIn: true, sessionExpired: false, userEmail: "nate@example.com",
      signOut: vi.fn(), signIn: vi.fn(), accessToken: "tok",
      authedFetch: vi.fn().mockResolvedValue({ ok: true, json: async () => ({ workflowId: "wf-new" }) }),
    };
  }

  // 2026-08-21: WorkflowCreateForm's trigger/action lists used to be
  // hardcoded JS constants -- now fetched from GET
  // /api/rules/vocabulary/{triggers,actions} (RulesController), backed by
  // JdbcWorkflowVocabularyStore's seeded rows plus candidate entries
  // merged in from docs/ontology.yaml. This fixture mirrors that real
  // seeded shape (same ids/labels the backend actually seeds) plus one
  // candidate of each kind, matching two of docs/ontology.yaml's real
  // 2026-08-21 additions (trigger_rf_tripwire_crossed, action_entry_light_on).
  function vocabularyFetch(overrides = {}) {
    return vi.fn((url) => {
      if (url.includes("/vocabulary/triggers")) {
        return Promise.resolve({ ok: true, json: async () => (overrides.triggers ?? [
          { id: "trigger_water_leak_detected", label: "Water leak detected", appliesToDeviceType: "WATER_LEAK_SENSOR", supported: true },
          { id: "trigger_water_leak_cleared", label: "Water leak cleared", appliesToDeviceType: "WATER_LEAK_SENSOR", supported: true },
          { id: "trigger_camera_detection", label: "Camera detects motion", appliesToDeviceType: "CAMERA", supported: true },
          { id: "trigger_rf_tripwire_crossed", label: "RF tripwire crossed", appliesToDeviceType: "RF_TRIPWIRE", supported: false },
        ]) });
      }
      if (url.includes("/vocabulary/actions")) {
        return Promise.resolve({ ok: true, json: async () => (overrides.actions ?? [
          { id: "action_main_water_valve_off", label: "Shut off the main water valve", needsTarget: true, targetDeviceId: "z2m-main_water_valve", privileged: false, supported: true },
          { id: "action_main_water_valve_open", label: "Open the main water valve", needsTarget: true, targetDeviceId: "z2m-main_water_valve", privileged: true, supported: true },
          { id: "notify_critical", label: "Send a critical notification", needsTarget: false, privileged: false, supported: true },
          { id: "log_event", label: "Log this event only", needsTarget: false, privileged: false, supported: true },
          { id: "action_entry_light_on", label: "Turn on the entry light", needsTarget: true, privileged: false, supported: false },
        ]) });
      }
      return Promise.resolve({ ok: true, json: async () => [] });
    });
  }

  it("the creation form defaults to device-triggered and excludes the privileged reopen action", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));

    expect(await screen.findByLabelText("Specifically")).toBeTruthy(); // only shown for DEVICE_EVENT
    const actionOptions = [...screen.getAllByRole("option")].map(o => o.textContent);
    expect(actionOptions).not.toContain("Open the main water valve");
  });

  it("switching to a person-triggered workflow hides the device-trigger fields and allows the reopen action", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");

    fireEvent.change(screen.getByLabelText("When"), { target: { value: "MANUAL" } });

    expect(screen.queryByLabelText("Specifically")).toBeNull();
    expect(screen.queryByLabelText("On this device (optional)")).toBeNull();
    const actionOptions = [...screen.getAllByRole("option")].map(o => o.textContent);
    expect(actionOptions).toContain("Open the main water valve");
  });

  it("switching back to device-triggered after picking the privileged action resets it to a valid choice", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");
    fireEvent.change(screen.getByLabelText("When"), { target: { value: "MANUAL" } });
    fireEvent.change(screen.getByDisplayValue("Shut off the main water valve"), { target: { value: "action_main_water_valve_open" } });

    fireEvent.change(screen.getByLabelText("When"), { target: { value: "DEVICE_EVENT" } });

    expect(screen.queryByDisplayValue("Open the main water valve")).toBeNull();
  });

  it("renders a candidate (not-yet-supported) trigger and action as disabled, not selectable, not hidden", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");

    const tripwireOption = screen.getByText(/RF tripwire crossed — not available yet/);
    expect(tripwireOption.disabled).toBe(true);
    const entryLightOption = screen.getByText(/Turn on the entry light — not available yet/);
    expect(entryLightOption.disabled).toBe(true);
    // A disabled option can never become the real selection this form submits.
    expect(screen.getByLabelText("Specifically").value).not.toBe("trigger_rf_tripwire_crossed");
  });

  it("locks the device field for an instance-specific action instead of offering a free picker", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[
      { deviceId: "z2m-main_water_valve", name: "Main water valve" },
      { deviceId: "z2m-leak_mech_room", name: "Mech room leak sensor" },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");
    // Default action row is the first supported one (Shut off the main
    // water valve) -- its vocabulary entry ships a fixed targetDeviceId.
    // "Main water valve" legitimately also appears as a plain <option> in
    // the trigger's own device-scoping picker below -- scope to the
    // locked-device element specifically, not a bare text search.

    const locked = document.querySelector(".workflow-action-locked-device");
    expect(locked?.textContent).toContain("Main water valve");
    expect(screen.queryByText("Choose a device…")).toBeNull();
  });

  // Part B (2026-09-02): WorkflowActionTargetValidator now rejects a
  // capability mismatch server-side -- this filter just stops a person
  // from picking a device that would be rejected on save. Devices are
  // given an unrelated `type` here so they're excluded from the trigger's
  // own device-scoping picker above, keeping each device name unique to
  // one <select> in the DOM for these assertions.
  it("filters the free device-target picker by the action's requiresCapability", async () => {
    vi.stubGlobal("fetch", vocabularyFetch({
      actions: [
        { id: "action_command_capable_only", label: "Command a capable device",
          needsTarget: true, requiresCapability: "COMMAND", privileged: false, supported: true },
      ],
    }));
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[
      { deviceId: "z2m-command-capable", name: "Command capable", type: "SMART_PLUG", attributes: { capabilities: ["COMMAND"] } },
      { deviceId: "z2m-telemetry-only", name: "Telemetry only", type: "SMART_PLUG", attributes: { capabilities: ["TELEMETRY"] } },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");

    expect(screen.getByText("Command capable")).toBeTruthy();
    expect(screen.queryByText("Telemetry only")).toBeNull();
  });

  it("lets a person override an instance-locked action's device via 'Change target device', and submits that override", async () => {
    const auth = mockAuth();
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={auth} devices={[
      { deviceId: "z2m-main_water_valve", name: "Main water valve", attributes: { capabilities: ["COMMAND"] } },
      { deviceId: "z2m-backup_valve", name: "Backup valve", attributes: { capabilities: ["COMMAND"] } },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");

    fireEvent.click(screen.getByText("Change target device"));
    // Seeded with the original default, not blank, so a person who doesn't
    // touch the picker still submits a valid target.
    expect(screen.getByDisplayValue("Main water valve")).toBeTruthy();
    expect(screen.queryByText(/^→ Main water valve/)).toBeNull();

    fireEvent.change(screen.getByDisplayValue("Main water valve"), { target: { value: "z2m-backup_valve" } });
    fireEvent.change(screen.getByPlaceholderText(/leak shutoff/i), { target: { value: "Backup valve test" } });
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalled());
    const body = JSON.parse(auth.authedFetch.mock.calls[0][1].body);
    expect(body.actions[0].targetDeviceId).toBe("z2m-backup_valve");
  });

  it("'Use default' reverts an overridden action back to its locked device", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[
      { deviceId: "z2m-main_water_valve", name: "Main water valve", attributes: { capabilities: ["COMMAND"] } },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");
    fireEvent.click(screen.getByText("Change target device"));
    expect(screen.getByDisplayValue("Main water valve")).toBeTruthy();

    fireEvent.click(screen.getByText(/Use default/));

    const locked = document.querySelector(".workflow-action-locked-device");
    expect(locked?.textContent).toContain("Main water valve");
    expect(screen.queryByText("Choose a device…")).toBeNull();
  });

  it("scopes the device-scoping picker to the selected trigger's own device type", async () => {
    vi.stubGlobal("fetch", vocabularyFetch());
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[
      { deviceId: "z2m-leak_mech_room", name: "Mech room leak sensor", type: "WATER_LEAK_SENSOR" },
      { deviceId: "z2m-driveway-cam", name: "Driveway camera", type: "CAMERA" },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");
    // Default trigger is Water leak detected (WATER_LEAK_SENSOR) -- the
    // camera shouldn't be offered as a device to scope this trigger to.

    expect(screen.getByLabelText("On this device (optional)")).toBeTruthy();
    expect(screen.getByText("Mech room leak sensor")).toBeTruthy();
    expect(screen.queryByText("Driveway camera")).toBeNull();
  });

  // 2026-08-27: trigger_mold_risk_detected's appliesToDeviceType is null
  // because humidity is reported by both TEMPERATURE_SENSOR and
  // HUMIDITY_SENSOR -- before appliesToField existed, null meant "show
  // every device of every type" here, not just humidity-reporting ones
  // (the exact gap the user hit trying to scope this workflow). Confirms
  // appliesToField takes priority and filters by reportsFields instead.
  it("scopes the device-scoping picker by reportsFields when the trigger sets appliesToField, not by device type", async () => {
    vi.stubGlobal("fetch", vocabularyFetch({
      triggers: [
        { id: "trigger_mold_risk_detected", label: "Mold risk (60% humidity or above)",
          appliesToDeviceType: null, appliesToField: "humidity", supported: true },
      ],
    }));
    render(<WorkflowRulesCard workflows={[]} auth={mockAuth()} devices={[
      { deviceId: "z2m-temp_kitchen", name: "Kitchen temp/humidity", type: "TEMPERATURE_SENSOR",
        attributes: { reportsFields: ["humidity", "temperature"] } },
      { deviceId: "ha-kidde-humidity", name: "Kidde humidity", type: "HUMIDITY_SENSOR",
        attributes: { reportsFields: ["humidity"] } },
      { deviceId: "z2m-driveway-cam", name: "Driveway camera", type: "CAMERA",
        attributes: { reportsFields: [] } },
    ]} />);
    fireEvent.click(screen.getByText("+ New Workflow"));
    await screen.findByLabelText("Specifically");

    expect(screen.getByText("Kitchen temp/humidity")).toBeTruthy();
    expect(screen.getByText("Kidde humidity")).toBeTruthy();
    expect(screen.queryByText("Driveway camera")).toBeNull();
  });

  it("Fire now is only offered for active MANUAL workflows, not device-triggered or draft ones", () => {
    render(<WorkflowRulesCard auth={mockAuth()} workflows={[
      { workflowId: "wf-manual-active", name: "Reopen valve", location: "cabin", triggerKind: "MANUAL", enabled: true, actions: [] },
      { workflowId: "wf-manual-draft", name: "Reopen valve (draft)", location: "cabin", triggerKind: "MANUAL", enabled: false, actions: [] },
      { workflowId: "wf-device", name: "Leak shutoff", location: "cabin", triggerKind: "DEVICE_EVENT", enabled: true, actions: [] },
    ]} />);

    expect(screen.getAllByText("Fire now")).toHaveLength(1);
  });

  it("tapping Fire now calls the fire endpoint for that workflow", async () => {
    const auth = mockAuth();
    render(<WorkflowRulesCard auth={auth} onChanged={vi.fn()} workflows={[
      { workflowId: "wf-manual-active", name: "Reopen valve", location: "cabin", triggerKind: "MANUAL", enabled: true, actions: [] },
    ]} />);

    fireEvent.click(screen.getByText("Fire now"));

    await waitFor(() => expect(auth.authedFetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/rules/workflows/wf-manual-active/fire"), expect.objectContaining({ method: "POST" })));
  });

  // 2026-08-21: RulesController.executions()/clearExecution() (GET
  // .../workflows/{id}/executions, POST .../executions/{id}/clear) already
  // existed server-side with zero frontend caller -- ROADMAP Phase 5's
  // "Active→Reset" reductive UI item. Covers the fix: History reveals real
  // execution rows, and Reset only appears while still active and posts
  // to the real clear endpoint (never a device command -- see
  // WorkflowExecutionHistory's own comment on why "Reset" must stay
  // bookkeeping-only, not an undo of the workflow's actions).
  describe("per-workflow execution history", () => {
    function executionsFetch(executions) {
      return vi.fn((url) => Promise.resolve({
        ok: true,
        json: async () => url.includes("/executions/recent") ? [] : executions,
      }));
    }

    it("History reveals fired/cleared status and per-action results, Reset only on an active execution", async () => {
      vi.stubGlobal("fetch", executionsFetch([
        { executionId: "exec-active", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: null, clearedBy: null,
          actionResults: [{ actionId: "a1", actionDefinitionId: "action_main_water_valve_off", success: true, commandStatus: "ACCEPTED" }] },
        { executionId: "exec-cleared", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: new Date().toISOString(), clearedBy: "AUTO",
          actionResults: [{ actionId: "a2", actionDefinitionId: "notify_critical", success: true }] },
      ]));
      render(<WorkflowRulesCard auth={mockAuth()} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);

      fireEvent.click(screen.getByText("History"));

      expect(await screen.findByText("Active")).toBeTruthy();
      expect(screen.getByText(/Cleared · AUTO/)).toBeTruthy();
      expect(screen.getByText(/action_main_water_valve_off: ACCEPTED/)).toBeTruthy();
      expect(screen.getAllByText("Reset")).toHaveLength(1); // only the active execution gets one
    });

    it("tapping Reset calls the clear endpoint for that execution, never a fire/activate endpoint", async () => {
      const auth = mockAuth();
      vi.stubGlobal("fetch", executionsFetch([
        { executionId: "exec-active", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: null, clearedBy: null, actionResults: [] },
      ]));
      render(<WorkflowRulesCard auth={auth} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);
      fireEvent.click(screen.getByText("History"));
      await screen.findByText("Reset");

      fireEvent.click(screen.getByText("Reset"));

      await waitFor(() => expect(auth.authedFetch).toHaveBeenCalledWith(
        expect.stringContaining("/api/rules/executions/exec-active/clear"), expect.objectContaining({ method: "POST" })));
      expect(auth.authedFetch).not.toHaveBeenCalledWith(expect.stringContaining("/fire"), expect.anything());
      expect(auth.authedFetch).not.toHaveBeenCalledWith(expect.stringContaining("/activate"), expect.anything());
    });

    it("shows no Reset control for a signed-out viewer -- Reset is a gated write, not a read", async () => {
      vi.stubGlobal("fetch", executionsFetch([
        { executionId: "exec-active", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: null, clearedBy: null, actionResults: [] },
      ]));
      render(<WorkflowRulesCard workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);

      fireEvent.click(screen.getByText("History"));

      expect(await screen.findByText("Active")).toBeTruthy();
      expect(screen.queryByText("Reset")).toBeNull();
    });
  });

  // The "Recent" half of Phase 5's "Active→Reset, Recent→Undo" item --
  // GET /api/rules/executions/recent already existed server-side with zero
  // frontend caller. "Mark seen" is deliberately named for what
  // POST .../view actually does (see RecentExecutionsList's own comment):
  // there is no real undo primitive in this engine.
  describe("recent (unviewed) executions", () => {
    it("shows unviewed executions resolved to their workflow's real name", async () => {
      vi.stubGlobal("fetch", vi.fn((url) => Promise.resolve({
        ok: true,
        json: async () => url.includes("/executions/recent")
          ? [{ executionId: "exec-1", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: null }]
          : [],
      })));
      render(<WorkflowRulesCard auth={mockAuth()} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);

      const recentHeading = await screen.findByText("Recent");
      // "Leak shutoff" legitimately appears twice on the page (the Recent
      // row's resolved name, and the workflow's own row in the main list
      // below) -- scope to the Recent section specifically.
      expect(within(recentHeading.closest(".workflow-recent-executions")).getByText("Leak shutoff")).toBeTruthy();
    });

    it("tapping Mark seen calls the view endpoint for that execution", async () => {
      const auth = mockAuth();
      vi.stubGlobal("fetch", vi.fn((url) => Promise.resolve({
        ok: true,
        json: async () => url.includes("/executions/recent")
          ? [{ executionId: "exec-1", workflowId: "wf-1", firedAt: new Date().toISOString(), clearedAt: null }]
          : [],
      })));
      render(<WorkflowRulesCard auth={auth} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);
      await screen.findByText("Mark seen");

      fireEvent.click(screen.getByText("Mark seen"));

      await waitFor(() => expect(auth.authedFetch).toHaveBeenCalledWith(
        expect.stringContaining("/api/rules/executions/exec-1/view"), expect.objectContaining({ method: "POST" })));
    });

    it("renders nothing when there is nothing unviewed, rather than an empty section", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
      render(<WorkflowRulesCard auth={mockAuth()} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [] },
      ]} />);

      await waitFor(() => expect(screen.queryByText(/no workflows configured/i)).toBeNull()); // sanity: real render happened
      expect(screen.queryByText("Recent")).toBeNull();
    });
  });

  // Phase 3 (Part C, 2026-09-02) -- health is an additive field on each
  // workflow from GET .../workflows (RulesController.listWorkflows()).
  // WorkflowRow fetches its own action vocabulary (same useWorkflowVocabulary
  // hook WorkflowCreateForm uses) to know a broken action's requiresCapability
  // for the fix-it picker's device filter.
  describe("workflow health badges and retarget (Part C)", () => {
    function healthFetch(actionsVocab = []) {
      return vi.fn((url) => {
        if (url.includes("/vocabulary/actions")) return Promise.resolve({ ok: true, json: async () => actionsVocab });
        return Promise.resolve({ ok: true, json: async () => [] });
      });
    }
    const brokenMainValveAction = {
      actionId: "wf-1-a0", actionDefinitionId: "action_main_water_valve_off", targetDeviceId: "z2m-main_water_valve",
      deviceExists: false, hasCapability: false, activeUseAllowed: false, online: false,
    };
    const mainValveVocab = { id: "action_main_water_valve_off", label: "Shut off the main water valve",
      needsTarget: true, requiresCapability: "COMMAND", targetDeviceId: "z2m-main_water_valve", privileged: false, supported: true };

    it("shows a Broken badge and a capability-filtered fix-it picker for a structurally unhealthy action", async () => {
      vi.stubGlobal("fetch", healthFetch([mainValveVocab]));
      render(<WorkflowRulesCard auth={mockAuth()} devices={[
        { deviceId: "z2m-backup_valve", name: "Backup valve", attributes: { capabilities: ["COMMAND"] } },
        { deviceId: "z2m-sensor", name: "Some sensor", attributes: { capabilities: ["TELEMETRY"] } },
      ]} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true,
          actions: [{ actionId: "wf-1-a0", actionDefinitionId: "action_main_water_valve_off", targetDeviceId: "z2m-main_water_valve" }],
          health: { status: "BROKEN", actions: [brokenMainValveAction] } },
      ]} />);

      expect(await screen.findByText("⚠ Broken")).toBeTruthy();
      expect(screen.getByText(/target device no longer exists/)).toBeTruthy();

      fireEvent.click(screen.getByText("Choose replacement device"));
      expect(screen.getByText("Backup valve")).toBeTruthy();
      expect(screen.queryByText("Some sensor")).toBeNull();
    });

    it("shows a Degraded badge with no fix-it picker when only reachability is the issue", async () => {
      vi.stubGlobal("fetch", healthFetch());
      render(<WorkflowRulesCard auth={mockAuth()} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [],
          health: { status: "DEGRADED", actions: [
            { actionId: "wf-1-a0", actionDefinitionId: "action_main_water_valve_off", targetDeviceId: "z2m-main_water_valve",
              deviceExists: true, hasCapability: true, activeUseAllowed: true, online: false },
          ] } },
      ]} />);

      expect(await screen.findByText("⚠ Degraded")).toBeTruthy();
      expect(screen.queryByText("Choose replacement device")).toBeNull();
    });

    it("shows no badge for a HEALTHY workflow", async () => {
      vi.stubGlobal("fetch", healthFetch());
      render(<WorkflowRulesCard auth={mockAuth()} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true, actions: [],
          health: { status: "HEALTHY", actions: [] } },
      ]} />);

      await screen.findByText("Leak shutoff");
      expect(screen.queryByText("⚠ Broken")).toBeNull();
      expect(screen.queryByText("⚠ Degraded")).toBeNull();
    });

    it("choosing a replacement and saving calls the retarget endpoint with the new device, then refreshes", async () => {
      const auth = mockAuth();
      const onChanged = vi.fn();
      vi.stubGlobal("fetch", healthFetch([mainValveVocab]));
      render(<WorkflowRulesCard auth={auth} onChanged={onChanged} devices={[
        { deviceId: "z2m-backup_valve", name: "Backup valve", attributes: { capabilities: ["COMMAND"] } },
      ]} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true,
          actions: [{ actionId: "wf-1-a0", actionDefinitionId: "action_main_water_valve_off", targetDeviceId: "z2m-main_water_valve" }],
          health: { status: "BROKEN", actions: [brokenMainValveAction] } },
      ]} />);
      await screen.findByText("⚠ Broken");

      fireEvent.click(screen.getByText("Choose replacement device"));
      fireEvent.change(screen.getByDisplayValue("Choose a replacement…"), { target: { value: "z2m-backup_valve" } });
      fireEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => expect(auth.authedFetch).toHaveBeenCalledWith(
        expect.stringContaining("/api/rules/workflows/wf-1/actions/wf-1-a0/retarget"),
        expect.objectContaining({ method: "POST" })));
      const [, options] = auth.authedFetch.mock.calls[0];
      expect(JSON.parse(options.body)).toEqual({ targetDeviceId: "z2m-backup_valve" });
      await waitFor(() => expect(onChanged).toHaveBeenCalled());
    });

    it("Save stays disabled until a replacement device is actually chosen", async () => {
      vi.stubGlobal("fetch", healthFetch([mainValveVocab]));
      render(<WorkflowRulesCard auth={mockAuth()} devices={[
        { deviceId: "z2m-backup_valve", name: "Backup valve", attributes: { capabilities: ["COMMAND"] } },
      ]} workflows={[
        { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true,
          actions: [{ actionId: "wf-1-a0", actionDefinitionId: "action_main_water_valve_off", targetDeviceId: "z2m-main_water_valve" }],
          health: { status: "BROKEN", actions: [brokenMainValveAction] } },
      ]} />);
      await screen.findByText("⚠ Broken");
      fireEvent.click(screen.getByText("Choose replacement device"));

      expect(screen.getByRole("button", { name: "Save" }).disabled).toBe(true);
    });
  });
});

describe("CameraNotifyToggle", () => {
  afterEach(cleanup);

  it("shows 'Notify me' (inactive) when this camera has no trigger_camera_detection workflow", () => {
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={vi.fn()} workflows={[]} onChanged={() => {}} />);
    expect(screen.getByText("Notify me")).toBeTruthy();
  });

  it("ignores a workflow that targets this camera as an action, not as the trigger", () => {
    const workflows = [{ workflowId: "wf-x", triggerDefinitionId: "trigger_water_leak_detected",
      triggerDeviceId: "z2m-leak_mech_room", actions: [{ targetDeviceId: "driveway" }] }];
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={vi.fn()} workflows={workflows} onChanged={() => {}} />);
    expect(screen.getByText("Notify me")).toBeTruthy();
  });

  it("ignores another camera's trigger_camera_detection workflow", () => {
    const workflows = [{ workflowId: "wf-other-cam", triggerDefinitionId: "trigger_camera_detection",
      triggerDeviceId: "home_aldrich_front", actions: [] }];
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={vi.fn()} workflows={workflows} onChanged={() => {}} />);
    expect(screen.getByText("Notify me")).toBeTruthy();
  });

  it("shows 'Notifying' (active) when this camera has a real, matching workflow", () => {
    const workflows = [{ workflowId: "wf-driveway", triggerDefinitionId: "trigger_camera_detection",
      triggerDeviceId: "driveway", actions: [{ actionDefinitionId: "notify_critical" }] }];
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={vi.fn()} workflows={workflows} onChanged={() => {}} />);
    expect(screen.getByText("Notifying")).toBeTruthy();
  });

  it("clicking while inactive creates a disabled workflow scoped to this camera, then activates it", async () => {
    const calls = [];
    const authedFetch = vi.fn((url, opts) => {
      calls.push({ url, opts });
      if (opts?.method === "POST" && url.endsWith("/api/rules/workflows")) {
        return Promise.resolve({ ok: true, json: async () => ({ workflowId: "wf-new", enabled: false }) });
      }
      if (opts?.method === "POST" && url.endsWith("/activate")) {
        return Promise.resolve({ ok: true, json: async () => ({ workflowId: "wf-new", enabled: true }) });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    });
    const onChanged = vi.fn();
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={authedFetch} workflows={[]} onChanged={onChanged} />);

    fireEvent.click(screen.getByText("Notify me"));

    await waitFor(() => expect(onChanged).toHaveBeenCalledOnce());
    const createCall = calls.find(c => c.url.endsWith("/api/rules/workflows") && !c.url.includes("activate"));
    const body = JSON.parse(createCall.opts.body);
    expect(body.triggerDefinitionId).toBe("trigger_camera_detection");
    expect(body.triggerDeviceId).toBe("driveway");
    expect(body.actions).toEqual([expect.objectContaining({ actionDefinitionId: "notify_critical" })]);
    expect(calls.some(c => c.url === "http://cabin/api/rules/workflows/wf-new/activate")).toBe(true);
  });

  it("clicking while active deletes the existing workflow", async () => {
    const workflows = [{ workflowId: "wf-driveway", triggerDefinitionId: "trigger_camera_detection",
      triggerDeviceId: "driveway", actions: [] }];
    const authedFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    const onChanged = vi.fn();
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={authedFetch} workflows={workflows} onChanged={onChanged} />);

    fireEvent.click(screen.getByText("Notifying"));

    await waitFor(() => expect(onChanged).toHaveBeenCalledOnce());
    expect(authedFetch).toHaveBeenCalledWith("http://cabin/api/rules/workflows/wf-driveway", { method: "DELETE" });
  });

  it("a failed toggle surfaces the error without calling onChanged", async () => {
    const authedFetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });
    const onChanged = vi.fn();
    render(<CameraNotifyToggle cameraName="driveway" apiBase="http://cabin" authedFetch={authedFetch} workflows={[]} onChanged={onChanged} />);

    fireEvent.click(screen.getByText("Notify me"));

    await waitFor(() => expect(screen.getByTitle(/not saved/i)).toBeTruthy());
    expect(onChanged).not.toHaveBeenCalled();
  });
});

describe("DeviceDiscoveryOverlay", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const candidateDevice = { deviceId: "z2m-x", name: "Discovered", type: "CONTACT_SENSOR", location: "cabin", attributes: {} };

  function mockDiscoveryFetch(result) {
    vi.stubGlobal("fetch", vi.fn((url, opts) => {
      if (String(url).endsWith("/discovery/run")) {
        return Promise.resolve({ ok: true, json: async () => ({ runId: "run-1" }) });
      }
      if (String(url).endsWith("/discovery/latest")) {
        return Promise.resolve({ ok: true, json: async () => result });
      }
      if (String(url).endsWith("/discovery/apply")) {
        return Promise.resolve({ ok: true, json: async () => ({ deviceId: "z2m-x", changed: true, deviceLifecycle: "ASSIGNED" }) });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    }));
  }

  const realMatch = {
    summary: "A SONOFF SNZB-04P contact sensor.",
    confidence: "high",
    suggestedName: "SONOFF SNZB-04P Contact Sensor",
    suggestedType: "CONTACT_SENSOR",
    suggestedCapabilities: ["TELEMETRY", "ACCESS_CONTROL"],
    installGuide: { mode: "summary", content: "Pair within 30 seconds of powering on." },
    sources: [{ url: "https://example.com/spec", title: "Spec sheet", snippet: "...", fetchedAt: "2026-08-13T00:00:00Z" }],
  };

  it("shows a loading state, then the result with sources once the poll resolves", async () => {
    mockDiscoveryFetch({ runId: "run-1", deviceId: "z2m-x", pending: false, matches: [realMatch] });
    render(<DeviceDiscoveryOverlay device={candidateDevice} mode="new" onClose={() => {}} onApplied={() => {}} />);

    expect(screen.getByText(/looking up discovered/i)).toBeTruthy();
    expect(await screen.findByText(realMatch.summary)).toBeTruthy();
    expect(screen.getByText("high confidence")).toBeTruthy();
    expect(screen.getByRole("link", { name: /spec sheet/i })).toHaveProperty("href", "https://example.com/spec");
  });

  it("shows an unverified notice when no sources came back", async () => {
    const noSourceMatch = { ...realMatch, confidence: "low", sources: [] };
    mockDiscoveryFetch({ runId: "run-1", deviceId: "z2m-x", pending: false, matches: [noSourceMatch] });
    render(<DeviceDiscoveryOverlay device={candidateDevice} mode="new" onClose={() => {}} onApplied={() => {}} />);

    expect(await screen.findByText(/no external sources were found/i)).toBeTruthy();
  });

  it("Import applies only the checked fields and calls onApplied", async () => {
    mockDiscoveryFetch({ runId: "run-1", deviceId: "z2m-x", pending: false, matches: [realMatch] });
    const onApplied = vi.fn();
    render(<DeviceDiscoveryOverlay device={candidateDevice} mode="new" onClose={() => {}} onApplied={onApplied} />);
    await screen.findByText(realMatch.summary);

    fireEvent.click(screen.getByRole("button", { name: /import this device/i }));

    await waitFor(() => expect(onApplied).toHaveBeenCalledOnce());
    const applyCall = fetch.mock.calls.find(([url]) => String(url).endsWith("/discovery/apply"));
    const body = JSON.parse(applyCall[1].body);
    expect(body.mode).toBe("new");
    expect(body.fields.name).toBe("SONOFF SNZB-04P Contact Sensor");
  });

  it("replace mode shows the current value struck through next to the suggested value", async () => {
    mockDiscoveryFetch({ runId: "run-1", deviceId: "z2m-x", pending: false, matches: [realMatch] });
    const assignedDevice = { ...candidateDevice, name: "Old name", type: "MOTION_SENSOR" };
    render(<DeviceDiscoveryOverlay device={assignedDevice} mode="replace" onClose={() => {}} onApplied={() => {}} />);

    await screen.findByText(realMatch.summary);
    expect(screen.getByText("Old name →")).toBeTruthy();
    expect(screen.getByRole("button", { name: /replace device settings with new definitions/i })).toBeTruthy();
  });

  it("shows an error state if the discovery/run call itself fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    render(<DeviceDiscoveryOverlay device={candidateDevice} mode="new" onClose={() => {}} onApplied={() => {}} />);

    expect(await screen.findByText(/didn't respond in time/i)).toBeTruthy();
  });

  it("surfaces the rate-limit guard's own message instead of the generic timeout copy", async () => {
    vi.stubGlobal("fetch", vi.fn((url) => {
      if (String(url).endsWith("/discovery/run")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({ error: "A discovery run for this device was started recently -- try again in 12s", retryAfterSeconds: 12 }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    }));
    render(<DeviceDiscoveryOverlay device={candidateDevice} mode="new" onClose={() => {}} onApplied={() => {}} />);

    expect(await screen.findByText(/try again in 12s/i)).toBeTruthy();
    expect(screen.queryByText(/didn't respond in time/i)).toBeNull();
  });
});

describe("Device Manager grouped ordering", () => {
  const devices = [
    { deviceId: "lock", type: "LOCK", state: "ONLINE", attributes: {} },
    { deviceId: "motion", type: "MOTION_SENSOR", state: "ONLINE", attributes: {} },
    { deviceId: "smoke", type: "SMOKE_ALARM", state: "ALARM", attributes: {} },
    { deviceId: "thermostat", type: "THERMOSTAT", state: "ONLINE", attributes: {} },
  ];
  const isAlarm = d => d.state === "ALARM" || d.state === "CRITICAL";

  it("persists group order independently from device order inside each group", () => {
    const grouped = buildOrderedDeviceGroups(
      devices,
      "workflow",
      ["HVAC", "Alerting", "Automations"],
      { Alerting: ["motion", "smoke"], Automations: ["lock"] },
      isAlarm,
    );

    expect(grouped.map(([name]) => name)).toEqual(["HVAC", "Alerting", "Automations"]);
    expect(grouped.find(([name]) => name === "Alerting")[1].map(d => d.deviceId))
      .toEqual(["smoke", "motion"]); // active alarm auto-pins within its own group
    expect(grouped.find(([name]) => name === "HVAC")[1].map(d => d.deviceId))
      .toEqual(["thermostat"]);
  });

  it("appends newly discovered groups and devices without disturbing saved peers", () => {
    const grouped = buildOrderedDeviceGroups(
      devices,
      "workflow",
      ["Automations"],
      { Alerting: ["motion"] },
      undefined,
    );

    expect(grouped.map(([name]) => name)).toEqual(["Automations", "Alerting", "HVAC"]);
    expect(grouped.find(([name]) => name === "Alerting")[1].map(d => d.deviceId))
      .toEqual(["motion", "smoke"]);
  });

  it("reorders by stable IDs and ignores invalid or no-op drops", () => {
    expect(reorderIds(["a", "b", "c"], "c", "a")).toEqual(["c", "a", "b"]);
    expect(reorderIds(["a", "b"], "missing", "a")).toEqual(["a", "b"]);
    expect(reorderIds(["a", "b"], "a", "a")).toEqual(["a", "b"]);
  });

  // Part D of the device-lifecycle plan -- the north star from the original
  // investigation: "ignore must mean... bump it below all other statuses of
  // devices on sorted lists of all devices." isIgnored is the symmetric
  // counterpart to isAlarm's pin-to-top.
  describe("IGNORED devices sort to the bottom (Part D)", () => {
    const isIgnored = d => d.attributes?.deviceLifecycle === "IGNORED";
    const devicesWithIgnored = [
      { deviceId: "old_sensor", type: "MOTION_SENSOR", state: "ONLINE", attributes: { deviceLifecycle: "IGNORED" } },
      { deviceId: "lock", type: "LOCK", state: "ONLINE", attributes: {} },
      { deviceId: "motion", type: "MOTION_SENSOR", state: "ONLINE", attributes: {} },
    ];

    it("moves an ignored device below its non-ignored group peers regardless of saved order", () => {
      const grouped = buildOrderedDeviceGroups(
        devicesWithIgnored, "type", [], { MOTION_SENSOR: ["old_sensor", "motion"] }, undefined, isIgnored,
      );
      expect(grouped.find(([name]) => name === "MOTION_SENSOR")[1].map(d => d.deviceId))
        .toEqual(["motion", "old_sensor"]);
    });

    it("ignored-sort runs after (and wins over) the alarm pin for a device that is somehow both", () => {
      const both = [
        { deviceId: "stale_alarm", type: "SMOKE_ALARM", state: "ALARM", attributes: { deviceLifecycle: "IGNORED" } },
        { deviceId: "active_alarm", type: "SMOKE_ALARM", state: "ALARM", attributes: {} },
        { deviceId: "quiet", type: "SMOKE_ALARM", state: "ONLINE", attributes: {} },
      ];
      const isAlarmLocal = d => d.state === "ALARM";
      const grouped = buildOrderedDeviceGroups(both, "type", [], {}, isAlarmLocal, isIgnored);
      expect(grouped.find(([name]) => name === "SMOKE_ALARM")[1].map(d => d.deviceId))
        .toEqual(["active_alarm", "quiet", "stale_alarm"]);
    });

    it("omitting isIgnored entirely leaves existing ordering behavior unchanged", () => {
      const grouped = buildOrderedDeviceGroups(devicesWithIgnored, "type", [], {}, undefined);
      expect(grouped.find(([name]) => name === "MOTION_SENSOR")[1].map(d => d.deviceId))
        .toEqual(["old_sensor", "motion"]);
    });
  });

  it("waits for device loading before migrating the legacy flat order", () => {
    expect(migrateLegacyDeviceOrder([], "workflow", ["motion", "lock"])).toBeNull();
    expect(migrateLegacyDeviceOrder(devices, "workflow", ["motion", "lock", "smoke"]))
      .toEqual({ Alerting: ["motion", "smoke"], Automations: ["lock"], HVAC: [] });
  });
});

// 2026-08-25 (user report): switching from See to Change lost whatever
// device you were looking at, Change showed devices in a different order
// than a saved See-mode reorder, and Change had no Group/Show controls or
// a way to snap back after narrowing them. All three fixed by hoisting
// useGroupedDraggableOrder up to DeviceManagerPanel (so See and Change
// read one shared computed order) and only clearing `selected` when
// switching to Add.
describe("DeviceManagerPanel — selection stickiness, shared order, Reset Filters", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

  function deviceManagerFetchMock() {
    return vi.fn((url) => {
      if (String(url).includes("checkin-status") || String(url).includes("checkin-details")) {
        return Promise.resolve({ ok: true, json: async () => ({}) });
      }
      if (String(url).includes("system/health")) {
        return Promise.resolve({ ok: true, json: async () => ({ online: 0, offline: 0, alarm: 0 }) });
      }
      return Promise.resolve({ ok: true, json: async () => [] }); // candidates / previously-exposed
    });
  }

  function renderPanel({ devices, workflows = [] }) {
    vi.stubGlobal("fetch", deviceManagerFetchMock());
    return render(
      <AppContext.Provider value={{ devices, workflows, activeLocation: "cabin", refreshDevices: vi.fn(), setActivePanel: vi.fn() }}>
        <DeviceManagerPanel />
      </AppContext.Provider>
    );
  }

  const twoDevices = [
    { deviceId: "d1", name: "Device One", type: "LOCK", state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" } },
    { deviceId: "d2", name: "Device Two", type: "LOCK", state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" } },
  ];

  // Each test flushes the mount-time health/checkin-status fetches (inside
  // act, via waitFor) before interacting -- otherwise those mocked
  // promises resolve after the test body returns and React warns about an
  // unwrapped act() update, even though nothing here asserts on them.
  it("keeps the selected device when switching from See to Change (only Add clears it)", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByText("Device One"));
    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    expect(screen.getByDisplayValue("Device One")).toBeTruthy();
  });

  // 2026-08-27 (user report): the selection itself carried over correctly
  // (test above), but neither See nor Change ever scrolled the still-
  // selected row into view on remount -- a person switching tabs had to
  // manually re-find it in any list of real length, making the carry-over
  // easy to miss even though it was technically working.
  it("scrolls the selected row into view when it's still selected after switching tabs", async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByText("Device One"));
    scrollIntoView.mockClear(); // only care about the switch below, not the initial click

    fireEvent.click(screen.getByRole("button", { name: "Change" }));

    expect(scrollIntoView).toHaveBeenCalled();
    delete Element.prototype.scrollIntoView;
  });

  it("clears the selection when switching to Add -- no device context makes sense there", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByText("Device One"));
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    // Returning to "see" remounts DmSeeView (its own key), which re-fires
    // its mount-time health/checkin fetches -- flush those before the
    // test ends so the resulting state update isn't unwrapped.
    const callsBeforeReturn = fetch.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "See" }));
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(callsBeforeReturn));
    expect(screen.queryByText("d1")).toBeNull(); // dm-detail-id, only shown when selected
  });

  it("Change mode renders the exact same saved grouping/order as See, with no Reorder control", async () => {
    localStorage.setItem("order.devices.cabin.type", JSON.stringify({ LOCK: ["m-lock", "z-lock", "a-lock"] }));
    const devices = ["a-lock", "z-lock", "m-lock"].map(id => ({
      deviceId: id, name: id, type: "LOCK", state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" },
    }));
    const { container } = renderPanel({ devices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    const namesInSee = [...container.querySelectorAll(".dm-row-name")].map(el => el.textContent);
    expect(namesInSee).toEqual(["m-lock", "z-lock", "a-lock"]);

    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    const namesInChange = [...container.querySelectorAll(".dm-row-name")].map(el => el.textContent);
    expect(namesInChange).toEqual(["m-lock", "z-lock", "a-lock"]);
    expect(screen.queryByText("Reorder")).toBeNull();
  });

  // The other half of "Open device" (ActiveConditionsCard) -- Device
  // Manager itself has to consume the pending target and clear it, or a
  // later unrelated panel switch back here would re-select a stale device.
  it("selects and clears a pendingDeviceFocus handed to it via context", async () => {
    const setPendingDeviceFocus = vi.fn();
    vi.stubGlobal("fetch", deviceManagerFetchMock());
    render(
      <AppContext.Provider value={{
        devices: twoDevices, workflows: [], activeLocation: "cabin",
        refreshDevices: vi.fn(), setActivePanel: vi.fn(),
        pendingDeviceFocus: "d2", setPendingDeviceFocus,
      }}>
        <DeviceManagerPanel />
      </AppContext.Provider>
    );
    await waitFor(() => expect(fetch).toHaveBeenCalled());

    expect(screen.getByText("d2")).toBeTruthy(); // dm-detail-id -- the pending device is now selected
    expect(setPendingDeviceFocus).toHaveBeenCalledWith(null);
  });

  it("force-expands a device group containing an active ALARM device, even if the user had collapsed it", async () => {
    localStorage.setItem("collapsed.deviceGroups.cabin.type", JSON.stringify({ LOCK: true }));
    const devices = [
      { deviceId: "d1", name: "Device One", type: "LOCK", state: "ALARM", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" } },
      { deviceId: "d2", name: "Device Two", type: "LOCK", state: "ONLINE", location: "cabin", attributes: { deviceLifecycle: "ASSIGNED" } },
    ];
    renderPanel({ devices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());

    expect(screen.getByText("Device One")).toBeTruthy();
    const caret = screen.getByLabelText(/lock has an active alarm and can't be collapsed/i);
    expect(caret.disabled).toBe(true);
  });

  it("collapses and expands a device group with no active alarm via its caret", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());

    expect(screen.getByText("Device One")).toBeTruthy();
    fireEvent.click(screen.getByLabelText(/^collapse lock$/i));
    expect(screen.queryByText("Device One")).toBeNull();
    fireEvent.click(screen.getByLabelText(/^expand lock$/i));
    expect(screen.getByText("Device One")).toBeTruthy();
  });

  it("Change mode offers the same Group/Parent-only/State controls as See", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    expect(screen.getByLabelText(/^group$/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Parent devices only" })).toBeTruthy();
    expect(screen.getByRole("button", { name: /^state/i })).toBeTruthy();
  });

  it("Reset Filters snaps Group/Parent-only/State back to defaults without deselecting the current device", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByText("Device One"));
    // Changing Group remounts DmSeeView (keyed on groupBy) -- flush its
    // re-fired mount-time fetches before continuing.
    let callsBefore = fetch.mock.calls.length;
    fireEvent.change(screen.getByLabelText(/^group$/i), { target: { value: "room" } });
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(callsBefore));

    fireEvent.click(screen.getByRole("button", { name: "Parent devices only" }));
    expect(screen.getByRole("button", { name: "Parent devices only" }).className).toMatch(/btn-ghost-active/);

    fireEvent.click(screen.getByRole("button", { name: /^state/i }));
    fireEvent.click(screen.getByLabelText("Candidates"));
    expect(screen.getByRole("button", { name: /^state/i }).textContent).toMatch(/2 selected/);

    callsBefore = fetch.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: /reset filters/i }));
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(callsBefore));
    expect(screen.getByLabelText(/^group$/i).value).toBe("type");
    expect(screen.getByRole("button", { name: "Parent devices only" }).className).not.toMatch(/btn-ghost-active/);
    expect(screen.getByRole("button", { name: /^state/i }).textContent).toMatch(/3 selected/);
    expect(screen.getByText("d1")).toBeTruthy(); // dm-detail-id -- still selected
  });

  it("Review previously exposed overrides Parent-only/State while checked", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());

    fireEvent.click(screen.getByLabelText(/review previously exposed/i));
    expect(screen.getByRole("button", { name: "Parent devices only" }).disabled).toBe(true);
    expect(screen.getByRole("button", { name: /^state/i }).disabled).toBe(true);

    fireEvent.click(screen.getByLabelText(/review previously exposed/i));
    expect(screen.getByRole("button", { name: "Parent devices only" }).disabled).toBe(false);
  });
});

// Covers the 2026-08-08 request: "I only see one node red... same context
// shift behavior for all locations" — Rules & Alerts should split per
// location in "Both" mode the same way Monitoring already does, and a
// location without its own configured Node-RED should say so rather than
// silently show Cabin's flows as if they were its own.
describe("RulesPanel — per-location Node-RED", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderWith(activeLocation) {
    return render(
      <AppContext.Provider value={{ activeLocation }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("shows only Cabin's flows, unlabeled as a fallback, when Cabin is the active location", () => {
    renderWith("cabin");
    fireEvent.click(screen.getByText("Load Node-RED"));
    expect(screen.getByTitle("Node-RED — Cabin")).toBeTruthy();
    expect(screen.queryByTitle("Node-RED — Home")).toBeNull();
    expect(screen.queryByText(/doesn't have its own Node-RED/)).toBeNull();
  });

  it("shows Home's section with a fallback hint when Home has no configured instance of its own", () => {
    renderWith("home");
    fireEvent.click(screen.getByText("Load Node-RED"));
    expect(screen.getByTitle("Node-RED — Home")).toBeTruthy();
    expect(screen.getByText(/Home doesn't have its own Node-RED instance configured yet/)).toBeTruthy();
  });

  it("splits into one section per location in Both mode, never flagging Cabin as a fallback", () => {
    renderWith("both");
    screen.getAllByText("Load Node-RED").forEach(btn => fireEvent.click(btn));
    expect(screen.getByTitle("Node-RED — Cabin")).toBeTruthy();
    expect(screen.getByTitle("Node-RED — Home")).toBeTruthy();
    expect(screen.getAllByText(/doesn't have its own Node-RED instance configured yet/)).toHaveLength(1);
  });

  // 2026-08-24: Node-RED runs with no auth configured on this instance and
  // sends no framing-protection headers -- see LocationRulesSection's own
  // comment. The iframe must not mount until a person explicitly asks for
  // it, so a browser that never opens this section never even gets the
  // Local Network Access prompt.
  it("does not mount the Node-RED iframe until Load Node-RED is clicked", () => {
    renderWith("cabin");
    expect(screen.queryByTitle("Node-RED — Cabin")).toBeNull();
    expect(screen.getByText("Load Node-RED")).toBeTruthy();
    fireEvent.click(screen.getByText("Load Node-RED"));
    expect(screen.getByTitle("Node-RED — Cabin")).toBeTruthy();
  });
});

// Found 2026-08-11 (user report, comparing the real product against
// impressive.llc's marketing site): the site showed a polished See/Think/Act
// water-pressure alert card with no real equivalent in the app -- Rules &
// Alerts was just a Node-RED link + a sidebar list, and that sidebar list
// itself already claimed things (active:true, "Alert + email") that the
// backend never actually did. This covers the real fix: AutomationAlertCard
// (backed by AutomationRuleService's now-real AUTOMATION_ALERT events) and
// the corrected BuiltinRules copy.
describe("humanizeRuleId", () => {
  it("turns a SCREAMING_SNAKE_CASE rule id into Title Case words", () => {
    expect(humanizeRuleId("WATER_PRESSURE_LOW")).toBe("Water Pressure Low");
    expect(humanizeRuleId("FREEZE_RISK")).toBe("Freeze Risk");
  });

  it("falls back to a generic label rather than crashing on a missing ruleId", () => {
    expect(humanizeRuleId(undefined)).toBe("Alert");
    expect(humanizeRuleId(null)).toBe("Alert");
  });

  // 2026-08-21: WorkflowRuleService.publishNotification() sets
  // ruleId="WORKFLOW_"+workflowId, and a generated workflowId (e.g.
  // "wf-leak-shutoff-1729123456789") has no underscores of its own to
  // Title Case sensibly -- the naive split would render something like
  // "Workflow Wf-leak-shutoff-1729123456789". Special-cased instead.
  it("shows a short, honest category for a workflow-engine-sourced ruleId instead of humanizing its generated id", () => {
    expect(humanizeRuleId("WORKFLOW_wf-leak-shutoff-1729123456789")).toBe("Workflow");
    expect(humanizeRuleId("WORKFLOW_UNCONFIRMED_a1b2c3")).toBe("Workflow Unconfirmed");
  });
});

describe("automationAlertSteps", () => {
  it("labels the Think step as unexplained without fabricating a delivery receipt", () => {
    const steps = automationAlertSteps({
      severity: "CRITICAL",
      payload: { ruleId: "WATER_PRESSURE_LOW", act: "Alert Nate" },
    });
    expect(steps.map(s => s.label)).toEqual(["SEE", "THINK", "ACT"]);
    expect(steps[1].headline).toBe("No routine explains it");
    expect(steps[2]).toMatchObject({
      headline: "Alert Nate",
      detail: "CRITICAL event published; delivery depends on the configured channel",
    });
  });

  it("labels Think/Act differently for a WARN alert -- an ordinary explanation, no push", () => {
    const steps = automationAlertSteps({
      severity: "WARN",
      payload: { ruleId: "WATER_PRESSURE_LOW", act: "Logged, no push" },
    });
    expect(steps[1].headline).toBe("An ordinary explanation exists");
    expect(steps[2].detail).toBe("Logged, no push");
  });
});

describe("current active alert projection", () => {
  // StatusChecksCard's collapse state persists to localStorage
  // (collapsed.statusChecks) -- both this block and the automation-alerts
  // block below share that key now that they're one merged component, so
  // a collapse-toggle test here would otherwise leak into and break the
  // other block's tests. Found via a real cross-block failure, not
  // precautionary.
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

  it("uses backend severity directly instead of a browser duration timer", () => {
    expect(alertLevelFor([])).toBeNull();
    expect(alertLevelFor([{ severity: "WARN" }])).toBe("warn");
    expect(alertLevelFor([{ severity: "WARN" }, { severity: "CRITICAL" }])).toBe("critical");
    expect(deriveNavAlertLevels([{ severity: "CRITICAL" }])).toEqual({
      DEVICE_MANAGER: "critical", MONITORING: "critical", RULES_ENGINE: "critical",
    });
  });

  // 2026-09-19 (user report): tab dots stayed lit with nothing to review. Live
  // data was 10 WARN device alerts, all 10 IGNORED, 7 of them for "home" while
  // viewing cabin -- the banner and Status Checks list said "nothing needs
  // attention" and the rail read the raw list anyway.
  describe("nav tab dots follow the same list as the banner and Status Checks", () => {
    const warn = (id, location = "cabin") => ({
      alertId: `device:${id}:missed-checkin`, sourceDeviceId: id, location,
      severity: "WARN", condition: "MISSED_CHECKIN", title: `${id} missed its check-in window`,
      evidenceAt: "2026-09-18T05:22:00Z",
    });
    const noLevel = { DEVICE_MANAGER: null, MONITORING: null, RULES_ENGINE: null };

    it("shows no dots once every alert has been ignored", () => {
      const alerts = [warn("a"), warn("b"), warn("c")];
      const acks = alerts.map(a => ({ alertKey: a.alertId, mode: "IGNORED" }));
      expect(navAlertLevelsFor(alerts, "cabin", [], acks)).toEqual(noLevel);
    });

    it("shows no dots for alerts that belong to a location that isn't selected", () => {
      expect(navAlertLevelsFor([warn("nas", "home"), warn("tv", "home")], "cabin", [])).toEqual(noLevel);
    });

    it("still shows a dot for an alert that hasn't been dealt with", () => {
      const alerts = [warn("a"), warn("b")];
      const acks = [{ alertKey: alerts[0].alertId, mode: "IGNORED" }];
      expect(navAlertLevelsFor(alerts, "cabin", [], acks).RULES_ENGINE).toBe("warn");
    });

    it("counts automation alerts, which the rail never saw before", () => {
      const event = { eventId: "e1", sourceDeviceId: "psi", eventType: "AUTOMATION_ALERT", severity: "CRITICAL",
        timestamp: "2026-09-18T05:22:00Z", payload: { ruleId: "WATER_PRESSURE_LOW", see: "Pressure dropped" } };
      expect(navAlertLevelsFor([], "cabin", [event]).DEVICE_MANAGER).toBe("critical");
    });

    it("agrees with the banner's own level for the same inputs", () => {
      const alerts = [warn("a"), warn("b", "home")];
      const acks = [{ alertKey: alerts[0].alertId, mode: "SNOOZED" }];
      const bannerLevel = alertLevelFor(mergeStatusCheckItems(alerts, "cabin", [], acks));
      expect(navAlertLevelsFor(alerts, "cabin", [], acks).RULES_ENGINE).toBe(bannerLevel);
    });

    it("treats severity case-insensitively", () => {
      expect(alertLevelFor([{ severity: "critical" }])).toBe("critical");
      expect(alertLevelFor([{ severity: "warn" }])).toBe("warn");
    });
  });

  it("shows a current backend condition and removes the old browser enable/reset controls", async () => {
    // This test supplies current conditions through context; keep the two
    // unrelated history/catalog requests pending so they cannot schedule an
    // unasserted state update after the synchronous checks below.
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "device:leak:alarm", location: "cabin", severity: "CRITICAL",
          condition: "DEVICE_ALARM", title: "Basement leak reports an alarm",
          detail: "The device's current runtime state is ALARM.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    expect(screen.getByText("Basement leak reports an alarm")).toBeTruthy();
    expect(screen.getByText(/Critical — 1 current condition/)).toBeTruthy();
    expect(screen.queryByText("Enable")).toBeNull();
    expect(screen.queryByText("Reset alerts")).toBeNull();
  });

  // 2026-09-16 (user report): capping the card's width made single-line
  // ellipsis truncation clip real content -- a title attribute is a
  // hover-only affordance, not keyboard- or touch-reachable, so it didn't
  // actually satisfy "I can read the full alert." "See more" is the real,
  // focusable replacement; this only checks the clamp CSS class is
  // removed on expand, since jsdom doesn't render -webkit-line-clamp
  // itself -- the actual text node was always present in the DOM.
  it("See more removes the line-clamp so the full title/detail are reachable, not just hinted at via hover", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const { container } = render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "a1", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "front_door missed its check-in window",
          detail: "No report arrived during the full grace window.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    expect(container.querySelector(".active-condition-clamp")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    expect(container.querySelector(".active-condition-clamp")).toBeNull();
    expect(screen.getByRole("button", { name: "See less" })).toBeTruthy();
  });

  // The See -> Think -> Act northstar's "Act" step: a real path to the
  // device that's actually causing the condition, not just more text.
  // 2026-09-18 (user directive): Open device moved out of the collapsed
  // row -- a person must go through See more (the real See/Think/Act
  // detail) before the further, deliberate choice to leave the alert and
  // drill into device config, rather than having a device-scoped action
  // sitting right next to an alert-scoped one at the collapsed level.
  it("Open device is not offered until the alert is expanded via See more", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "a1", sourceDeviceId: "leak_mech_room", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "Mech Room Leak missed its check-in window",
          detail: "No report arrived during the full grace window.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    expect(screen.queryByRole("button", { name: /open device/i })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    expect(screen.getByRole("button", { name: /open device/i })).toBeTruthy();
  });

  it("Open device sends the alert's real sourceDeviceId to Device Manager instead of leaving the person to re-find it", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const setActivePanel = vi.fn();
    const setPendingDeviceFocus = vi.fn();
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "a1", sourceDeviceId: "leak_mech_room", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "Mech Room Leak missed its check-in window",
          detail: "No report arrived during the full grace window.",
        }],
        setActivePanel, setPendingDeviceFocus,
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    fireEvent.click(screen.getByRole("button", { name: /open device/i }));
    expect(setPendingDeviceFocus).toHaveBeenCalledWith("leak_mech_room");
    expect(setActivePanel).toHaveBeenCalledWith("DEVICE_MANAGER");
  });

  it("doesn't offer Open device when an alert has no sourceDeviceId, even expanded", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "a1", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "front_door missed its check-in window",
          detail: "No report arrived during the full grace window.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    expect(screen.queryByRole("button", { name: /open device/i })).toBeNull();
  });

  it("won't let a CRITICAL current condition be collapsed out of view", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "device:leak:alarm", location: "cabin", severity: "CRITICAL",
          condition: "DEVICE_ALARM", title: "Basement leak reports an alarm",
          detail: "The device's current runtime state is ALARM.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    const caret = screen.getByLabelText(/critical condition is active and can't be collapsed/i);
    expect(caret.disabled).toBe(true);
    fireEvent.click(caret); // no-op: still can't collapse
    expect(screen.getByText("Basement leak reports an alarm")).toBeTruthy();
  });

  it("lets a WARN-only current condition collapse normally via its caret", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "device:missed", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "front_door missed its check-in window",
          detail: "No report arrived during the grace window.",
        }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    const caret = screen.getByLabelText(/^collapse status checks$/i);
    expect(caret.disabled).toBe(false);
    fireEvent.click(caret);
    expect(screen.queryByText("front_door missed its check-in window")).toBeNull();
  });

  it("renders rule status from the backend catalog with honest ownership", async () => {
    vi.stubGlobal("fetch", vi.fn((url) => Promise.resolve({
      ok: true,
      json: async () => url.includes("/api/alerts/rules") ? [{
        ruleId: "FREEZE_RISK", name: "Freeze Risk", trigger: "Temperature < 38.0°F",
        action: "Publishes a CRITICAL AUTOMATION_ALERT", severity: "CRITICAL",
        enabled: true, owner: "CABIN_BACKEND", configurationMode: "DEPLOY_TIME", editable: false,
      }] : [],
    })));
    render(
      <AppContext.Provider value={{ activeLocation: "cabin" }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    expect(await screen.findByText("Freeze Risk")).toBeTruthy();
    // Each fact is its own layer-3 chip now (was one joined string).
    for (const chip of ["CABIN_BACKEND", "deploy time", "read only"]) {
      expect(screen.getAllByText(chip).length).toBeGreaterThan(0);
    }
  });
});

// 2026-09-18 (user report, annotated screenshot): the banner showed a
// count with no way to act on it, and counted device alerts only while
// the merged Status Checks box below counted device+automation -- two
// different numbers for what looked like the same thing. Covers both
// fixes directly: the banner is a real, clickable button now, and its
// count matches mergeStatusCheckItems' real total instead of just
// activeAlerts.length.
describe("AlertControls", () => {
  afterEach(cleanup);

  it("counts device AND automation alerts together, not just device ones", () => {
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{ alertId: "a1", location: "cabin", severity: "WARN", condition: "MISSED_CHECKIN", title: "x", detail: "y" }],
        automationAlerts: [{ eventId: "e1", severity: "WARN", timestamp: new Date().toISOString(), payload: { ruleId: "FREEZE_RISK", see: "z" } }],
        automationAlertsLoading: false,
      }}>
        <AlertControls panelId="RULES_ENGINE" />
      </AppContext.Provider>
    );

    // 1 device + 1 automation = 2, not 1 -- the exact drift this fixes.
    expect(screen.getByText(/Attention — 2 current conditions/)).toBeTruthy();
  });

  it("clicking the banner navigates to Rules & Alerts", () => {
    const setActivePanel = vi.fn();
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{ alertId: "a1", location: "cabin", severity: "WARN", condition: "MISSED_CHECKIN", title: "x", detail: "y" }],
        automationAlerts: [],
        automationAlertsLoading: false,
        setActivePanel,
      }}>
        <AlertControls panelId="DEVICE_MANAGER" />
      </AppContext.Provider>
    );

    fireEvent.click(screen.getByRole("button"));
    expect(setActivePanel).toHaveBeenCalledWith("RULES_ENGINE");
  });

  it("stays a plain, non-interactive banner when the location genuinely hasn't resolved yet", () => {
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: [], activeAlerts: [],
        automationAlerts: [], automationAlertsLoading: true,
      }}>
        <AlertControls panelId="RULES_ENGINE" />
      </AppContext.Provider>
    );

    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText(/Current alert status unavailable/)).toBeTruthy();
  });
});

// 2026-09-18: AutomationAlertCard was merged into StatusChecksCard (same
// box as device-condition alerts, see that component's own comment) --
// these scenarios still apply, just against the merged card's compact-row
// layout. Automation-sourced rows now start collapsed like every other
// row; See/Think/Act content (and tags) only render once expanded, so
// tests that need that content click "See more" first instead of finding
// it always-visible.
describe("StatusChecksCard — automation alerts (via RulesPanel)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

  // 2026-09-18: useAutomationAlerts moved from RulesPanel's subtree up to
  // root App() (see its own comment -- the fetch is now shared with
  // AlertControls' nav banner, one source of truth for the total instead
  // of two independently-drifting ones). StatusChecksCard reads it from
  // context now, so these tests supply automationAlerts/
  // automationAlertsLoading directly instead of mocking the fetch that
  // used to happen inside this subtree. WorkflowRulesCard/
  // OptimizationOpportunitiesCard/BuiltinRules (RulesPanel's other
  // children) still fetch their own data on mount -- stub fetch with a
  // permanently-pending promise so those calls don't hit real network or
  // throw, same as "current active alert projection"'s own tests do.
  //
  // activeAlertLocations defaults to whichever locations activeLocation
  // implies -- StatusChecksCard deliberately won't claim "no status
  // checks" while the device-alert side hasn't confirmed a location's
  // status yet (same honesty rule as before), so a test asserting the
  // real empty state needs to represent a location that's actually
  // finished loading, not just omit it.
  function renderWith(activeLocation, automationAlerts, extra = {}) {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const loc = activeLocation || "cabin";
    const activeAlertLocations = loc === "both" ? ["cabin", "home"] : [loc];
    return render(
      <AppContext.Provider value={{ activeLocation: loc, activeAlertLocations, automationAlerts, automationAlertsLoading: false, ...extra }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("renders the See/Think/Act flow from a real CRITICAL AUTOMATION_ALERT event, matching the marketing scenario", () => {
    renderWith("cabin", [{
      eventId: "e1", sourceDeviceId: "psi_mech_room", eventType: "AUTOMATION_ALERT",
      severity: "CRITICAL", timestamp: new Date().toISOString(),
      payload: {
        ruleId: "WATER_PRESSURE_LOW",
        see: "Pressure dropped below the safe range.",
        think: "The cabin is away, no fixture is expected to be running, and the mechanical room sensor reports 26.0 PSI.",
        act: "Alert Nate",
        tags: ["CABIN - AWAY", "26.0 PSI", "UNEXPECTED USE"],
      },
    }]);

    expect(screen.getByText("Pressure dropped below the safe range.")).toBeTruthy();
    expect(screen.getByText(/mechanical room sensor reports 26.0 PSI/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    expect(screen.getByText("No routine explains it")).toBeTruthy();
    expect(screen.getAllByText("Alert Nate").length).toBeGreaterThan(0);
  });

  it("won't let a CRITICAL automation alert be collapsed out of view", () => {
    renderWith("cabin", [{
      eventId: "e-crit", sourceDeviceId: "psi_mech_room", eventType: "AUTOMATION_ALERT",
      severity: "CRITICAL", timestamp: new Date().toISOString(),
      payload: {
        ruleId: "WATER_PRESSURE_LOW",
        see: "Pressure dropped below the safe range.",
        think: "The cabin is away and the mechanical room sensor reports 26.0 PSI.",
        act: "Alert Nate", tags: [],
      },
    }]);

    const caret = screen.getByLabelText(/critical condition is active and can't be collapsed/i);
    expect(caret.disabled).toBe(true);
    fireEvent.click(caret); // no-op: still can't collapse
    expect(screen.getByText("Pressure dropped below the safe range.")).toBeTruthy();
  });

  it("shows an honest empty state instead of a stale or fabricated alert when there's nothing to report", () => {
    renderWith("cabin", []);

    expect(screen.getByText(/No status checks need attention/)).toBeTruthy();
  });

  // 2026-08-21: WorkflowRuleService.publishNotification() reuses this
  // card's exact {see,think,act,tags,ruleId} shape for WORKFLOW_ACTION
  // events (docs/ontology.yaml's notify_critical entity) -- covers that a
  // WORKFLOW_ACTION event renders through the same merged-row markup as a
  // plain AUTOMATION_ALERT one, not just the latter.
  it("also surfaces WORKFLOW_ACTION events, not just AUTOMATION_ALERT ones", () => {
    renderWith("cabin", [{
      eventId: "e2", sourceDeviceId: "z2m-leak_mech_room", eventType: "WORKFLOW_ACTION",
      severity: "CRITICAL", timestamp: new Date().toISOString(),
      payload: {
        ruleId: "WORKFLOW_wf-leak-shutoff-1", see: "Water leak detected",
        think: "Human-configured workflow 'Leak shutoff' matched this event",
        act: "Shut off main water valve + Notify", tags: ["WORKFLOW"],
      },
    }]);

    // The row's meta line (title-row sibling <span>) carries the
    // humanized rule category now, replacing the old dedicated
    // .automation-alert-category badge. Date+time lives in its own
    // sibling span (see "shows a dtm for every entry" below), not baked
    // into this one anymore.
    const row = screen.getByText("Water leak detected");
    expect(row.closest(".active-condition-title-row").querySelector("span").textContent).toBe("Workflow");
  });

  // 2026-09-19 (user directive): "show dtm for each entry -- since historic
  // context is the purpose of showing these entries, we need that."
  it("shows a dtm (date+time, not just time-of-day) for every entry", () => {
    const ts = new Date("2026-09-15T15:45:00Z").toISOString();
    renderWith("cabin", [{
      eventId: "e1", sourceDeviceId: "psi_mech_room", eventType: "AUTOMATION_ALERT",
      severity: "WARN", timestamp: ts,
      payload: { ruleId: "WATER_PRESSURE_LOW", see: "Pressure dropped" },
    }]);

    const row = screen.getByText("Pressure dropped");
    const timestampSpan = row.closest(".active-condition-title-row").querySelector(".active-condition-timestamp");
    expect(timestampSpan.textContent).toContain("Sep");
    expect(timestampSpan.textContent).toContain("15");
  });

  // useAutomationAlerts' own real fetch (URL shape, cabin/home/both
  // attempts, the broadened eventTypePrefix list, sort order) is a
  // separate concern from how StatusChecksCard renders whatever it's
  // given -- covered directly against the hook's real behavior instead
  // of through this describe block, which now only supplies pre-fetched
  // data via context. See "useAutomationAlerts" below.

  it("shows a real recent list (more than just the single latest alert)", () => {
    const now = Date.now();
    renderWith("cabin", [
      { eventId: "older", sourceDeviceId: "d1", eventType: "AUTOMATION_ALERT", severity: "WARN",
        timestamp: new Date(now - 60_000).toISOString(), payload: { ruleId: "FREEZE_RISK", see: "Older alert" } },
      { eventId: "newer", sourceDeviceId: "d2", eventType: "AUTOMATION_ALERT", severity: "CRITICAL",
        timestamp: new Date(now).toISOString(), payload: { ruleId: "WATER_PRESSURE_LOW", see: "Newer alert" } },
    ]);

    expect(screen.getByText("Newer alert")).toBeTruthy();
    expect(screen.getByText("Older alert")).toBeTruthy();
  });

});

// 2026-09-19 (user report, annotated screenshot): the same alert appeared as
// separate rows -- one condition is one row, its repeats are history.
describe("mergeStatusCheckItems — one row per condition", () => {
  const deviceAlert = (deviceId, overrides = {}) => ({
    alertId: `device:${deviceId}:missed-checkin`, sourceDeviceId: deviceId, location: "cabin",
    severity: "WARN", condition: "MISSED_CHECKIN", title: "main_water_valve missed its check-in window",
    detail: "No report arrived during the full grace window.", evidenceAt: "2026-09-18T05:22:00Z",
    ...overrides,
  });
  const automationEvent = (eventId, timestamp, sourceDeviceId = "psi_mech_room") => ({
    eventId, sourceDeviceId, eventType: "AUTOMATION_ALERT", severity: "WARN", timestamp,
    payload: { ruleId: "WATER_PRESSURE_LOW", see: "Pressure dropped" },
  });

  it("collapses two device records with the same name/location/condition into one row", () => {
    const items = mergeStatusCheckItems(
      [deviceAlert("z2m-main_water_valve"), deviceAlert("ha-main_water_valve")], "cabin", []);
    expect(items).toHaveLength(1);
    expect(items[0].alertKeys.sort()).toEqual([
      "device:ha-main_water_valve:missed-checkin", "device:z2m-main_water_valve:missed-checkin"]);
  });

  it("shows an identical instant once, not once per duplicate record", () => {
    const [item] = mergeStatusCheckItems(
      [deviceAlert("a"), deviceAlert("b")], "cabin", []);
    expect(item.occurrences).toEqual(["2026-09-18T05:22:00Z"]);
  });

  it("keeps different conditions and different names as separate rows", () => {
    const items = mergeStatusCheckItems([
      deviceAlert("a"),
      deviceAlert("b", { title: "Kidde detector missed its check-in window" }),
      deviceAlert("c", { alertId: "device:c:alarm", condition: "DEVICE_ALARM", severity: "CRITICAL" }),
    ], "cabin", []);
    expect(items).toHaveLength(3);
  });

  it("collapses repeated firings of one automation rule on one device, newest as the representative", () => {
    const items = mergeStatusCheckItems([], "cabin", [
      automationEvent("old", "2026-09-18T01:00:00Z"),
      automationEvent("new", "2026-09-18T03:00:00Z"),
      automationEvent("mid", "2026-09-18T02:00:00Z"),
    ]);
    expect(items).toHaveLength(1);
    expect(items[0].raw.eventId).toBe("new");
    expect(items[0].occurrences).toEqual(["2026-09-18T03:00:00Z", "2026-09-18T02:00:00Z", "2026-09-18T01:00:00Z"]);
  });

  it("keeps the same rule firing on two different devices as two rows", () => {
    const items = mergeStatusCheckItems([], "cabin", [
      automationEvent("e1", "2026-09-18T01:00:00Z", "psi_mech_room"),
      automationEvent("e2", "2026-09-18T01:00:00Z", "psi_other_room"),
    ]);
    expect(items).toHaveLength(2);
  });

  it("gives a group a stable id that doesn't change when a newer occurrence arrives", () => {
    const before = mergeStatusCheckItems([], "cabin", [automationEvent("e1", "2026-09-18T01:00:00Z")]);
    const after = mergeStatusCheckItems([], "cabin", [
      automationEvent("e1", "2026-09-18T01:00:00Z"), automationEvent("e2", "2026-09-18T02:00:00Z")]);
    expect(after[0].id).toBe(before[0].id);
  });

  it("acknowledging every member's key removes the whole group", () => {
    const items = mergeStatusCheckItems(
      [deviceAlert("a"), deviceAlert("b")], "cabin", [],
      [{ alertKey: "device:a:missed-checkin" }, { alertKey: "device:b:missed-checkin" }]);
    expect(items).toHaveLength(0);
  });
});

describe("StatusChecksCard — duplicates render as one row with history", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

  function renderDuplicates(extra = {}) {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const dup = (deviceId, evidenceAt) => ({
      alertId: `device:${deviceId}:missed-checkin`, sourceDeviceId: deviceId, location: "cabin",
      severity: "WARN", condition: "MISSED_CHECKIN", title: "main_water_valve missed its check-in window",
      detail: "No report arrived.", evidenceAt,
    });
    return render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: ["cabin"],
        activeAlerts: [dup("z2m-main_water_valve", "2026-09-18T05:22:00Z"), dup("ha-main_water_valve", "2026-09-18T05:22:00Z")],
        automationAlerts: [
          { eventId: "e1", sourceDeviceId: "psi", eventType: "AUTOMATION_ALERT", severity: "WARN",
            timestamp: "2026-09-18T01:00:00Z", payload: { ruleId: "FREEZE_RISK", see: "Freeze risk" } },
          { eventId: "e2", sourceDeviceId: "psi", eventType: "AUTOMATION_ALERT", severity: "WARN",
            timestamp: "2026-09-18T02:00:00Z", payload: { ruleId: "FREEZE_RISK", see: "Freeze risk" } },
        ],
        ...extra,
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("shows each alert once, however many records or events sit behind it", () => {
    renderDuplicates();
    expect(screen.getAllByText("main_water_valve missed its check-in window")).toHaveLength(1);
    expect(screen.getAllByText("Freeze risk")).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "See more" })).toHaveLength(2);
  });

  it("expands exactly the row that was clicked, and lists every distinct occurrence under it", () => {
    renderDuplicates();
    const freezeRow = screen.getByText("Freeze risk").closest(".active-condition");
    fireEvent.click(within(freezeRow).getByRole("button", { name: "See more" }));

    expect(screen.getAllByRole("button", { name: "See less" })).toHaveLength(1);
    expect(within(freezeRow).getByText("Seen 2 times")).toBeTruthy();
    expect(within(freezeRow).getAllByRole("listitem")).toHaveLength(2);
  });

  it("doesn't show an occurrence list when there's only one occurrence", () => {
    renderDuplicates();
    const valveRow = screen.getByText("main_water_valve missed its check-in window").closest(".active-condition");
    fireEvent.click(within(valveRow).getByRole("button", { name: "See more" }));
    expect(within(valveRow).queryByText(/Seen \d+ times/)).toBeNull();
  });

  it("Ignore for now acknowledges every record behind the row, so no twin reappears", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const authedFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) });
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: ["cabin"],
        activeAlerts: ["a", "b"].map(id => ({
          alertId: `device:${id}:missed-checkin`, sourceDeviceId: id, location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "Twin missed its check-in window", detail: "x",
          evidenceAt: "2026-09-18T05:22:00Z" })),
        alertAcknowledgments: [], refreshAlertAcknowledgments: vi.fn(),
      }}>
        <RulesPanel auth={{ authedFetch }} />
      </AppContext.Provider>
    );
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    fireEvent.click(screen.getByRole("button", { name: "Ignore for now" }));

    await waitFor(() => {
      const keys = authedFetch.mock.calls
        .filter(([url]) => url.includes("/api/alerts/acknowledgments"))
        .map(([, options]) => JSON.parse(options.body).alertKey);
      expect(keys.sort()).toEqual(["device:a:missed-checkin", "device:b:missed-checkin"]);
    });
  });
});

describe("mergeStatusCheckItems — acknowledgment filtering", () => {
  it("filters out an item whose alertKey has an active acknowledgment", () => {
    const items = mergeStatusCheckItems(
      [{ alertId: "device:leak_mech_room:missed-checkin", location: "cabin", severity: "WARN",
         condition: "MISSED_CHECKIN", title: "x", detail: "y" }],
      "cabin", [],
      [{ alertKey: "device:leak_mech_room:missed-checkin", mode: "IGNORED" }]
    );
    expect(items).toHaveLength(0);
  });

  it("leaves an item alone when its alertKey has no acknowledgment", () => {
    const items = mergeStatusCheckItems(
      [{ alertId: "device:leak_mech_room:missed-checkin", location: "cabin", severity: "WARN",
         condition: "MISSED_CHECKIN", title: "x", detail: "y" }],
      "cabin", [],
      [{ alertKey: "device:some_other_device:missed-checkin", mode: "IGNORED" }]
    );
    expect(items).toHaveLength(1);
  });

  it("derives a stable automation alertKey from ruleId+sourceDeviceId, filterable the same way", () => {
    const items = mergeStatusCheckItems([], "cabin",
      [{ eventId: "e1", sourceDeviceId: "psi_mech_room", severity: "WARN",
         timestamp: new Date().toISOString(), payload: { ruleId: "WATER_PRESSURE_LOW", see: "x" } }],
      [{ alertKey: "automation:WATER_PRESSURE_LOW:psi_mech_room", mode: "IGNORED" }]
    );
    expect(items).toHaveLength(0);
  });
});

// 2026-09-18 (user directive): "give the user the ability to a) ignore for
// now, b) let me know if it happens again in the next hour/day/week/month."
// Rendered through RulesPanel (not StatusChecksCard directly) since the
// actions need auth threaded from its own prop, matching how Open device's
// tests already work.
describe("StatusChecksCard — ignore/snooze actions", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderExpanded(alertOverrides = {}) {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const authedFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) });
    const refreshAlertAcknowledgments = vi.fn();
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin",
        activeAlertLocations: ["cabin"],
        activeAlerts: [{
          alertId: "device:leak_mech_room:missed-checkin", sourceDeviceId: "leak_mech_room",
          location: "cabin", severity: "WARN", condition: "MISSED_CHECKIN",
          title: "Mech Room Leak missed its check-in window",
          detail: "No report arrived during the full grace window.",
          ...alertOverrides,
        }],
        alertAcknowledgments: [], refreshAlertAcknowledgments,
      }}>
        <RulesPanel auth={{ authedFetch }} />
      </AppContext.Provider>
    );
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    return { authedFetch, refreshAlertAcknowledgments };
  }

  it("offers Ignore for now and Remind me in only once expanded, not in the collapsed row", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: ["cabin"],
        activeAlerts: [{ alertId: "a1", location: "cabin", severity: "WARN",
          condition: "MISSED_CHECKIN", title: "x", detail: "y" }],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );

    expect(screen.queryByRole("button", { name: "Ignore for now" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "See more" }));
    expect(screen.getByRole("button", { name: "Ignore for now" })).toBeTruthy();
  });

  // RulesPanel's other children (WorkflowRulesCard, OptimizationOpportunitiesCard,
  // BuiltinRules) also call authedFetch on mount through this same auth prop --
  // find the acknowledgments call specifically rather than assuming call order.
  function findAcknowledgmentCall(authedFetch) {
    return authedFetch.mock.calls.find(([url]) => url.includes("/api/alerts/acknowledgments"));
  }

  it("Ignore for now POSTs mode IGNORED with the alert's real key, then refreshes", async () => {
    const { authedFetch, refreshAlertAcknowledgments } = renderExpanded();

    fireEvent.click(screen.getByRole("button", { name: "Ignore for now" }));

    await waitFor(() => expect(findAcknowledgmentCall(authedFetch)).toBeTruthy());
    const [url, options] = findAcknowledgmentCall(authedFetch);
    expect(url).toContain("/api/alerts/acknowledgments");
    const body = JSON.parse(options.body);
    expect(body).toEqual({ alertKey: "device:leak_mech_room:missed-checkin", mode: "IGNORED", snoozedUntil: null });
    await waitFor(() => expect(refreshAlertAcknowledgments).toHaveBeenCalled());
  });

  it("Remind me in shows the four durations and snoozing POSTs a real future timestamp", async () => {
    const { authedFetch } = renderExpanded();

    fireEvent.click(screen.getByRole("button", { name: /remind me in/i }));
    for (const label of ["1 hour", "1 day", "1 week", "1 month"]) {
      expect(screen.getByRole("button", { name: label })).toBeTruthy();
    }

    const before = Date.now();
    fireEvent.click(screen.getByRole("button", { name: "1 day" }));

    await waitFor(() => expect(findAcknowledgmentCall(authedFetch)).toBeTruthy());
    const body = JSON.parse(findAcknowledgmentCall(authedFetch)[1].body);
    expect(body.mode).toBe("SNOOZED");
    const snoozedUntilMs = new Date(body.snoozedUntil).getTime();
    // Within a generous window of "now + 1 day" -- not asserting exact
    // equality against a second `Date.now()` call, which would be flaky.
    expect(snoozedUntilMs).toBeGreaterThan(before + 23 * 60 * 60 * 1000);
    expect(snoozedUntilMs).toBeLessThan(before + 25 * 60 * 60 * 1000);
  });
});

// 2026-09-19 (user directive): "for each alert, only one expansion path
// (drill-down path) can be open in the box" -- and clicking elsewhere in
// the UI while one is open retracts it back to the plain list rather than
// leaving a stale expanded row once attention has moved elsewhere.
describe("StatusChecksCard — single-open drill-down", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderTwoAlerts() {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    return render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: ["cabin"],
        activeAlerts: [
          { alertId: "a1", location: "cabin", severity: "WARN", condition: "MISSED_CHECKIN",
            title: "First alert", detail: "First detail" },
          { alertId: "a2", location: "cabin", severity: "WARN", condition: "MISSED_CHECKIN",
            title: "Second alert", detail: "Second detail" },
        ],
      }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("opening a second entry's See more closes whichever one was already open", () => {
    renderTwoAlerts();

    fireEvent.click(screen.getAllByRole("button", { name: "See more" })[0]);
    expect(screen.getAllByRole("button", { name: "See less" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "See more" })).toHaveLength(1);

    // The one remaining "See more" belongs to the second entry -- opening
    // it must close the first entry's drill-down, never leave both open.
    fireEvent.click(screen.getAllByRole("button", { name: "See more" })[0]);
    expect(screen.getAllByRole("button", { name: "See less" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "See more" })).toHaveLength(1);
  });

  it("clicking outside the box collapses whichever drill-down is open", () => {
    renderTwoAlerts();

    fireEvent.click(screen.getAllByRole("button", { name: "See more" })[0]);
    expect(screen.getByRole("button", { name: "See less" })).toBeTruthy();

    fireEvent.mouseDown(document.body);

    expect(screen.queryByRole("button", { name: "See less" })).toBeNull();
    expect(screen.getAllByRole("button", { name: "See more" })).toHaveLength(2);
  });

  it("a click inside the box (on the other entry's collapsed row) does not count as outside", () => {
    renderTwoAlerts();

    fireEvent.click(screen.getAllByRole("button", { name: "See more" })[0]);
    fireEvent.mouseDown(screen.getByText("Second alert"));

    // Still expanded -- a click on unrelated content inside the same box
    // isn't "elsewhere in the UI".
    expect(screen.getByRole("button", { name: "See less" })).toBeTruthy();
  });
});

// 2026-09-18: useAutomationAlerts moved to root App() (see its own
// comment) so AlertControls and StatusChecksCard share one fetch/one
// total instead of two independently-drifting ones. Tested directly
// against a tiny host component rather than indirectly through a large
// consumer, matching this file's own stated preference for testing
// extracted logic directly.
describe("useAutomationAlerts", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function Host({ activeLocation, authedFetch }) {
    const { alerts, loading } = useAutomationAlerts(activeLocation, authedFetch);
    if (loading) return <p>loading</p>;
    return <ul>{alerts.map(a => <li key={a.eventId}>{a.payload.see}</li>)}</ul>;
  }

  it("queries both cabin and home when activeLocation is both", async () => {
    const fetchMock = vi.fn((url) => Promise.resolve({
      ok: true,
      json: async () => url.startsWith("http://home-hub:8080")
        ? [{ eventId: "home1", sourceDeviceId: "d3", eventType: "WORKFLOW_ACTION", severity: "WARN",
              timestamp: new Date().toISOString(), payload: { ruleId: "WORKFLOW_wf-home-1", see: "Home alert" } }]
        : [{ eventId: "cabin1", sourceDeviceId: "d4", eventType: "AUTOMATION_ALERT", severity: "WARN",
              timestamp: new Date().toISOString(), payload: { ruleId: "FREEZE_RISK", see: "Cabin alert" } }],
    }));

    render(<Host activeLocation="both" authedFetch={fetchMock} />);

    expect(await screen.findByText("Cabin alert")).toBeTruthy();
    expect(screen.getByText("Home alert")).toBeTruthy();
  });

  it("requests the broadened eventTypePrefix list, not just AUTOMATION_ALERT", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });

    render(<Host activeLocation="cabin" authedFetch={fetchMock} />);

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) =>
      url.includes("eventTypePrefix=AUTOMATION_ALERT,WORKFLOW_ACTION,WORKFLOW_UNCONFIRMED"))).toBe(true));
  });
});

// Covers the 2026-08-08 request: drop the vestigial "Family" wording from
// this panel (it configures the whole instance, not just family
// settings), and make the Google Account / Platform / Remote Access
// cards reflect real, dynamic state instead of hardcoded JSX text. See
// isLocationDeployed's sibling entity instance_template_config in
// docs/ontology.yaml for the backend side of this.
describe("FamilyConfigPanel", () => {
  afterEach(cleanup);

  function renderPanel({ auth, config = {} } = {}) {
    return render(
      <AppContext.Provider value={{ config, locationCfg: LOCATIONS_CABIN }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
  }
  const LOCATIONS_CABIN = { haUrl: "http://cabin-hub:8123" };

  it("no longer says Family anywhere in the header", () => {
    renderPanel();
    expect(screen.getByText("Configuration")).toBeTruthy();
    expect(screen.queryByText(/Family Config/i)).toBeFalsy();
  });

  it("shows the signed-in Google account and a switch-account control", () => {
    const signIn = () => {};
    renderPanel({ auth: { userEmail: "nate@example.com", signIn } });
    expect(screen.getByText(/Signed in as nate@example.com/)).toBeTruthy();
    expect(screen.getByText("Switch Google Account")).toBeTruthy();
  });

  it("offers a sign-in control when no Google account is signed in", () => {
    renderPanel({ auth: { userEmail: null, signIn: () => {} } });
    expect(screen.getByText(/Not signed in/)).toBeTruthy();
    expect(screen.getByText("Sign in with Google")).toBeTruthy();
  });

  const remoteCard = () => screen.getByText("Remote Access").closest(".config-card");

  it("displays the configured platform from real backend config", () => {
    renderPanel({ config: { platformName: "Test Platform", platform: "A test VM", remoteAccess: "Tailscale,WireGuard" } });
    expect(screen.getByText("A test VM")).toBeTruthy();
    expect(screen.getByText("Test Platform")).toBeTruthy();
  });

  it("lists each configured way in as a chip, with a one-line note for the ones it knows", () => {
    renderPanel({ config: { remoteAccess: "Tailscale,SSH via Tailscale,Cloudflare Tunnel,WireGuard" } });
    const card = within(remoteCard());
    for (const chip of ["Tailscale", "SSH via Tailscale", "Cloudflare Tunnel", "WireGuard"]) {
      expect(card.getAllByText(chip).some(el => el.className === "meta-chip")).toBe(true);
    }
    expect(card.getByText(/Private network for the app, SSH and admin tools/)).toBeTruthy();
    expect(card.getByText(/Shell access over the private network/)).toBeTruthy();
    expect(card.getByText(/Public HTTPS address for chosen pages/)).toBeTruthy();
    // WireGuard is shown but has no note (not a path this app documents).
    expect(remoteCard().querySelectorAll(".remote-access-notes li")).toHaveLength(3);
  });

  it("defaults remote access to Tailscale when config hasn't loaded yet", () => {
    renderPanel({ config: {} });
    expect(within(remoteCard()).getAllByText("Tailscale").some(el => el.className === "meta-chip")).toBe(true);
  });

  // Reported 2026-09-20: the card showed a bare command with no explanation of
  // what a clone needs, what to change in it, or how to run it.
  it("explains how to connect a new clone: what it needs, what to change, how to run it", () => {
    renderPanel({ config: {} });
    const clone = remoteCard().querySelector("details.remote-access-clone");
    expect(clone).toBeTruthy();
    expect(within(clone).getByText("Connect a new clone")).toBeTruthy();
    const text = clone.textContent;
    // needs
    expect(text).toMatch(/Tailscale account/);
    expect(text).toMatch(/LAN subnet/);
    expect(text).toMatch(/two sites can't both advertise 192\.168\.1\.0\/24/);
    // the command, with the parts to change as placeholders rather than one site's real values
    const command = clone.querySelector("code.code-block").textContent;
    expect(command).toContain("tailscale up --ssh --accept-routes");
    expect(command).toContain("--hostname=<site>-hub");
    expect(command).toContain("--advertise-routes=<subnet>/24");
    expect(command).not.toContain("home-hub");
    expect(command.split("\n").length).toBe(4);   // install line + three-line command, not collapsed onto one line
    // what to change, and what to do after
    expect(text).toMatch(/the clone's own name/);
    expect(text).toMatch(/approve the advertised route/);
    expect(text).toMatch(/CABIN_INSTANCE_REMOTE_ACCESS/);
  });

  it("is collapsed until asked for, so the card stays short on a phone", () => {
    renderPanel({ config: {} });
    expect(remoteCard().querySelector("details.remote-access-clone").hasAttribute("open")).toBe(false);
  });

  it("shows one Platform card, wide, instead of a Platform box and a separate Platform Info box", () => {
    renderPanel({ config: { platformName: "Test Platform", platform: "A test VM" } });
    expect(screen.queryByText("Platform Info")).toBeNull();
    const platform = screen.getByText("Platform").closest(".config-card");
    expect(platform.className).toContain("config-card-wide");
    expect(within(platform).getByText("A test VM")).toBeTruthy();
  });
});

// Tier 1 guest share links -- see the plan's "Guest Access Model" section.
// GuestAccessCard isn't itself exported (it's a plain internal sub-
// component of FamilyConfigPanel, same as ConfigCard) -- tested through
// its parent, matching how this file already tests several other
// internal-only sub-components.
describe("FamilyConfigPanel — Guest Access (Tier 1 share links)", () => {
  afterEach(cleanup);

  // FamilyConfigPanel also renders PlatformInfoCard (Bug #5) and
  // ManagedUsersCard (Tier 2), which independently call
  // /api/system/platform-info and /api/managed-users on mount through this
  // same authedFetch mock -- route both to their own stub responses so
  // neither ever consumes a queued /api/access-tokens response meant for
  // these tests, and never trips these tests' own positional-call assertions.
  function mockAuth(accessTokenResponses) {
    const queue = [...accessTokenResponses];
    const authedFetch = vi.fn((url) => {
      if (url.includes("/api/system/platform-info")) {
        return Promise.resolve({ ok: true, json: async () => ({ versions: {}, hardware: [], aiDisclosure: null }) });
      }
      if (url.includes("/api/managed-users")) {
        return Promise.resolve({ ok: true, json: async () => [] });
      }
      return Promise.resolve(queue.shift());
    });
    return { authedFetch };
  }

  function accessTokenCalls(auth) {
    return auth.authedFetch.mock.calls.filter(([url]) => url.includes("/api/access-tokens"));
  }

  function renderPanel(auth) {
    return render(
      <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
  }

  it("lists existing share links returned by the API", async () => {
    const auth = mockAuth([{ ok: true, json: async () => [
      { id: "tok-1", label: "Insurance Claim", scope: ["dashboard", "device_states"], expiresAt: null, revokedAt: null },
    ] }]);

    renderPanel(auth);

    expect(await screen.findByText("Insurance Claim")).toBeTruthy();
    expect(screen.getByText(/dashboard, device_states/)).toBeTruthy();
  });

  it("creates a new link with only the checked scopes and shows the full URL exactly once", async () => {
    const auth = mockAuth([
      { ok: true, json: async () => [] },
      { ok: true, json: async () => ({ id: "tok-2", token: "secret-xyz", label: "Contractor", scope: ["device_states"] }) },
      { ok: true, json: async () => [{ id: "tok-2", label: "Contractor", scope: ["device_states"], revokedAt: null }] },
    ]);

    renderPanel(auth);
    await waitFor(() => expect(accessTokenCalls(auth).length).toBe(1));

    fireEvent.change(screen.getByPlaceholderText(/Label, e.g\./), { target: { value: "Contractor" } });
    // All four start checked -- uncheck the three not wanted, leaving only device_states.
    fireEvent.click(screen.getByLabelText("Dashboard"));
    fireEvent.click(screen.getByLabelText("Alerts"));
    fireEvent.click(screen.getByLabelText("Historical readings"));
    fireEvent.click(screen.getByText("Create link"));

    expect(await screen.findByText(/view\/secret-xyz/)).toBeTruthy();
    const createBody = JSON.parse(accessTokenCalls(auth)[1][1].body);
    expect(createBody.scope).toEqual(["device_states"]);
  });

  it("revokes a link and the list reflects it without a page reload", async () => {
    const auth = mockAuth([
      { ok: true, json: async () => [{ id: "tok-3", label: "Old Contractor", scope: ["dashboard"], revokedAt: null }] },
      { ok: true, json: async () => ({}) },
      { ok: true, json: async () => [{ id: "tok-3", label: "Old Contractor", scope: ["dashboard"], revokedAt: "2026-09-01T00:00:00Z" }] },
    ]);

    renderPanel(auth);
    await screen.findByText("Old Contractor");
    fireEvent.click(screen.getByText("Revoke"));

    expect(await screen.findByText("Revoked")).toBeTruthy();
    expect(accessTokenCalls(auth)[1][0]).toContain("/api/access-tokens/tok-3");
    expect(accessTokenCalls(auth)[1][1].method).toBe("DELETE");
  });

  it("degrades to an empty list instead of crashing when the request is unauthenticated", async () => {
    const auth = mockAuth([{ ok: false, status: 401, json: async () => ({ error: "Missing bearer token" }) }]);

    renderPanel(auth);

    expect(await screen.findByText("No share links yet.")).toBeTruthy();
  });
});

// Tier 2 managed users (WSJF #3, D12). ManagedUsersCard isn't itself
// exported -- tested through its parent, same pattern as GuestAccessCard
// above. FamilyConfigPanel also renders GuestAccessCard and PlatformInfoCard
// at the same time, each independently calling authedFetch on mount -- route
// by URL (not call order) so those two never consume a response queued for
// these tests, matching the "Platform Info" describe block's own approach.
describe("FamilyConfigPanel — Managed Users (Tier 2)", () => {
  afterEach(cleanup);

  function mockAuth(managedUserResponses) {
    const queue = [...managedUserResponses];
    const authedFetch = vi.fn((url) => {
      if (url.includes("/api/system/platform-info")) {
        return Promise.resolve({ ok: true, json: async () => ({ versions: {}, hardware: [], aiDisclosure: null }) });
      }
      if (url.includes("/api/access-tokens")) {
        return Promise.resolve({ ok: true, json: async () => [] });
      }
      return Promise.resolve(queue.shift());
    });
    return { authedFetch };
  }

  function managedUserCalls(auth) {
    return auth.authedFetch.mock.calls.filter(([url]) => url.includes("/api/managed-users"));
  }

  function renderPanel(auth) {
    return render(
      <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
  }

  it("lists existing managed users with their role and status", async () => {
    const auth = mockAuth([{ ok: true, json: async () => [
      { id: "u1", email: "alice@example.com", name: "Alice", role: "VIEWER", active: true },
    ] }]);

    renderPanel(auth);

    expect(await screen.findByText("Alice")).toBeTruthy();
    expect(screen.getByText(/alice@example\.com · Viewer \(read-only\)/)).toBeTruthy();
  });

  it("creates a new managed user with the selected role", async () => {
    const auth = mockAuth([
      { ok: true, json: async () => [] },
      { ok: true, json: async () => ({ id: "u2", email: "bob@example.com", name: "Bob", role: "HOUSEHOLD_MEMBER", active: true }) },
      { ok: true, json: async () => [{ id: "u2", email: "bob@example.com", name: "Bob", role: "HOUSEHOLD_MEMBER", active: true }] },
    ]);

    renderPanel(auth);
    await waitFor(() => expect(managedUserCalls(auth).length).toBe(1));

    fireEvent.change(screen.getByPlaceholderText("Email address"), { target: { value: "bob@example.com" } });
    fireEvent.change(screen.getByPlaceholderText("Name"), { target: { value: "Bob" } });
    fireEvent.change(screen.getByDisplayValue("Viewer (read-only)"), { target: { value: "HOUSEHOLD_MEMBER" } });
    fireEvent.click(screen.getByText("Add managed user"));

    expect(await screen.findByText("Bob")).toBeTruthy();
    const createBody = JSON.parse(managedUserCalls(auth)[1][1].body);
    expect(createBody).toEqual({ email: "bob@example.com", name: "Bob", role: "HOUSEHOLD_MEMBER" });
  });

  it("deactivates a managed user and the list reflects it without a page reload", async () => {
    const auth = mockAuth([
      { ok: true, json: async () => [{ id: "u3", email: "carl@example.com", name: "Carl", role: "VIEWER", active: true }] },
      { ok: true, json: async () => ({ id: "u3", active: false }) },
      { ok: true, json: async () => [{ id: "u3", email: "carl@example.com", name: "Carl", role: "VIEWER", active: false }] },
    ]);

    renderPanel(auth);
    await screen.findByText("Carl");
    fireEvent.click(screen.getByText("Deactivate"));

    expect(await screen.findByText(/deactivated/)).toBeTruthy();
    expect(managedUserCalls(auth)[1][0]).toContain("/api/managed-users/u3/deactivate");
    expect(managedUserCalls(auth)[1][1].method).toBe("POST");
  });

  it("invite shows a confirmation message", async () => {
    const auth = mockAuth([
      { ok: true, json: async () => [{ id: "u4", email: "dana@example.com", name: "Dana", role: "VIEWER", active: true }] },
      { ok: true, json: async () => ({ sent: true }) },
    ]);

    renderPanel(auth);
    await screen.findByText("Dana");
    fireEvent.click(screen.getByText("Invite"));

    expect(await screen.findByText("Invite sent.")).toBeTruthy();
  });

  it("degrades to an empty list instead of crashing when the request is unauthenticated", async () => {
    const auth = mockAuth([{ ok: false, status: 401, json: async () => ({ error: "Missing bearer token" }) }]);

    renderPanel(auth);

    expect(await screen.findByText("No managed users yet.")).toBeTruthy();
  });
});

// Bug #5 (2026-09 bug sprint): admin-only versions + hardware catalog + AI
// disclosure. PlatformInfoCard isn't itself exported -- tested through its
// parent, same pattern as GuestAccessCard above.
describe("FamilyConfigPanel — Platform Info (Bug #5)", () => {
  afterEach(cleanup);

  // FamilyConfigPanel also renders GuestAccessCard, which independently
  // calls /api/access-tokens on mount through the same authedFetch mock --
  // route by URL (like mockFetchByUrl elsewhere in this file) so that call
  // gets its own empty-array shape instead of crashing on a platform-info body.
  function mockAuth(platformInfoResponse) {
    return { authedFetch: vi.fn((url) => {
      if (url.includes("/api/system/platform-info")) return Promise.resolve(platformInfoResponse);
      return Promise.resolve({ ok: true, json: async () => [] });
    }) };
  }

  function renderPanel(auth) {
    return render(
      <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
  }

  it("shows live integration versions from the API", async () => {
    const auth = mockAuth({ ok: true, json: async () => ({
      versions: { cabinBackend: "0.1.0", homeAssistant: "2026.9.1", zigbee2mqtt: "1.35.0", ollama: "0.3.12", mqttBroker: "not exposed by Mosquitto over MQTT -- no version topic to read" },
      hardware: [],
      aiDisclosure: null,
    }) });

    renderPanel(auth);

    expect(await screen.findByText("2026.9.1")).toBeTruthy();
    expect(screen.getByText("1.35.0")).toBeTruthy();
    expect(screen.getByText("0.3.12")).toBeTruthy();
    expect(screen.getByText("Home Assistant")).toBeTruthy();
  });

  it("shows the static hardware catalog rows", async () => {
    const auth = mockAuth({ ok: true, json: async () => ({
      versions: {},
      hardware: [
        { category: "Host", description: "Lenovo ThinkCentre M920q" },
        { category: "Refrigeration", description: "No refrigeration appliance is currently paired" },
      ],
      aiDisclosure: null,
    }) });

    renderPanel(auth);

    expect(await screen.findByText("Lenovo ThinkCentre M920q")).toBeTruthy();
    expect(screen.getByText("No refrigeration appliance is currently paired")).toBeTruthy();
  });

  it("shows the AI-inference disclosure naming the model and Tailscale-only exposure", async () => {
    const auth = mockAuth({ ok: true, json: async () => ({
      versions: {}, hardware: [],
      aiDisclosure: { model: "llama3.2:3b (Ollama)", hostedWhere: "Locally, on the cabin M920q", networkExposure: "Tailscale-only", dataHandling: "No prompt is ever sent to an external AI service." },
    }) });

    renderPanel(auth);

    expect(await screen.findByText(/llama3.2:3b/)).toBeTruthy();
    expect(screen.getByText(/Tailscale-only/)).toBeTruthy();
  });

  it("shows an admin-required message rather than a raw error on 403", async () => {
    const auth = mockAuth({ ok: false, status: 403, json: async () => ({ error: "forbidden" }) });

    renderPanel(auth);

    expect(await screen.findByText(/Admin access required/)).toBeTruthy();
  });
});

// The public /view/{token} view -- deliberately uses the global fetch, not
// authedFetch, since a guest by definition has no Google session; the
// token itself (appended as ?t=) is the credential, validated server-side
// by GoogleAuthInterceptor's guest-token path.
describe("GuestDashboard (Tier 1 share links, /view/{token})", () => {
  afterEach(cleanup);

  // Bug #1: this component used to fire a single Promise.all over exactly
  // two hardcoded endpoints, so a token scoped to anything other than
  // {device_states + alerts_read} together tripped the shared .catch and
  // showed "invalid link" for a perfectly valid, differently-scoped token.
  // Each of these mocks routes by URL (not call order) since the real
  // component now fetches every scope's endpoint independently and
  // concurrently -- a 403 for an out-of-scope endpoint must never surface
  // as the generic invalid-link error, only a genuine 401 may.
  const mockFetchByUrl = (handlers) => vi.fn((url) => {
    for (const [match, response] of handlers) {
      if (url.includes(match)) return Promise.resolve(response);
    }
    return Promise.resolve({ ok: false, status: 403, json: async () => ({}) });
  });

  it("device_states scope shows devices, other sections stay empty without an error", async () => {
    vi.stubGlobal("fetch", mockFetchByUrl([
      ["/api/devices", { ok: true, json: async () => [
        { deviceId: "z2m-main_water_valve", name: "main_water_valve", state: "ONLINE", location: "cabin", lastSeen: "2026-09-01T12:00:00Z" },
      ] }],
    ]));

    render(<GuestDashboard token="secret-abc" />);

    expect(await screen.findByText("main_water_valve")).toBeTruthy();
    expect(screen.queryByText(/isn't valid, has expired/)).toBeFalsy();
  });

  it("alerts_read scope shows active alerts (continues to work as before)", async () => {
    vi.stubGlobal("fetch", mockFetchByUrl([
      ["/api/alerts/active", { ok: true, json: async () => [
        { id: "a1", severity: "CRITICAL", message: "Water leak detected", timestamp: "2026-09-01T11:00:00Z" },
      ] }],
    ]));

    render(<GuestDashboard token="secret-abc" />);

    expect(await screen.findByText(/Water leak detected/)).toBeTruthy();
  });

  it("dashboard scope shows the platform name from /api/dashboard/config", async () => {
    vi.stubGlobal("fetch", mockFetchByUrl([
      ["/api/dashboard/config", { ok: true, json: async () => ({ platformName: "Cumberland Cabin" }) }],
    ]));

    render(<GuestDashboard token="secret-abc" />);

    expect(await screen.findByText("Cumberland Cabin")).toBeTruthy();
  });

  it("observations_read scope shows historical readings via reported-fields + telemetry-history", async () => {
    vi.stubGlobal("fetch", mockFetchByUrl([
      ["/api/events/reported-fields", { ok: true, json: async () => ({ "z2m-temp_kitchen": ["humidity"] }) }],
      ["/api/events/telemetry-history", { ok: true, json: async () => [
        { day: "2026-09-01T00:00:00Z", avg: 47.5, min: 40, max: 55, sampleCount: 90 },
      ] }],
    ]));

    render(<GuestDashboard token="secret-abc" />);

    expect(await screen.findByText(/z2m-temp_kitchen — humidity/)).toBeTruthy();
    expect(screen.getByText(/avg 47.5/)).toBeTruthy();
  });

  it("a fresh token appends the token to every request regardless of scope", async () => {
    const fetchMock = mockFetchByUrl([]);
    vi.stubGlobal("fetch", fetchMock);

    render(<GuestDashboard token="secret-abc" />);

    await screen.findByText(/doesn't currently grant access/);
    fetchMock.mock.calls.forEach(call => expect(call[0]).toContain("t=secret-abc"));
  });

  it("shows a not-valid message only on a genuine 401, not a scope-mismatch 403", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 401 }));

    render(<GuestDashboard token="bad-token" />);

    expect(await screen.findByText(/isn't valid, has expired, or has been revoked/)).toBeTruthy();
  });

  it("a token granted no covered scope shows a distinct message, not the invalid-link error", async () => {
    vi.stubGlobal("fetch", mockFetchByUrl([])); // everything 403s

    render(<GuestDashboard token="secret-abc" />);

    expect(await screen.findByText(/doesn't currently grant access to any data/)).toBeTruthy();
    expect(screen.queryByText(/isn't valid, has expired/)).toBeFalsy();
  });
});

// Tier 2 managed users (WSJF #3, D12) -- where a managed user's browser
// lands right after clicking their emailed magic link. Deliberately uses
// the global fetch, not authedFetch, matching GuestDashboard above: a
// managed user by definition has no session yet at this point. On success
// this writes straight to the same localStorage keys useGoogleAuth() reads
// on mount, then navigates to "/" -- jsdom logs a harmless "Not
// implemented: navigation" line for that assignment (same as the existing
// window.location.reload() precedent elsewhere in this file), so these
// tests assert on the localStorage write itself rather than the navigation.
describe("MagicLinkLanding (Tier 2 magic link, /auth/magic/{token})", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => { cleanup(); localStorage.clear(); });

  it("consuming a valid token stores the managed session and role", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        sessionToken: "sess-abc", email: "carol@example.com", name: "Carol",
        role: "HOUSEHOLD_MEMBER", expiresAt: "2026-12-01T00:00:00Z",
      }),
    }));

    render(<MagicLinkLanding token="link-xyz" />);

    await waitFor(() => expect(localStorage.getItem("managedSessionToken")).toBe("sess-abc"));
    expect(localStorage.getItem("managedSessionEmail")).toBe("carol@example.com");
    expect(localStorage.getItem("managedSessionRole")).toBe("HOUSEHOLD_MEMBER");
    expect(Number(localStorage.getItem("managedSessionExpiresAt"))).toBe(new Date("2026-12-01T00:00:00Z").getTime());
  });

  it("an invalid/expired token shows the server's error message and stores nothing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ error: "This link is invalid, expired, already used, or the account is no longer active" }),
    }));

    render(<MagicLinkLanding token="stale-token" />);

    expect(await screen.findByText(/invalid, expired, already used/)).toBeTruthy();
    expect(localStorage.getItem("managedSessionToken")).toBeNull();
  });

  it("a network failure shows a distinct, actionable message rather than hanging", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));

    render(<MagicLinkLanding token="link-xyz" />);

    expect(await screen.findByText(/Couldn't reach the cabin server/)).toBeTruthy();
  });
});

// Sprint 5 WSJF #1 (r5 handover, "Optimization Opportunities"). Tested
// standalone (not through RulesPanel, whose Node-RED/Kafka/WorkflowRulesCard
// siblings all fetch on mount too) -- OptimizationOpportunitiesCard is
// exported specifically to make this possible cleanly.
describe("OptimizationOpportunitiesCard", () => {
  afterEach(cleanup);

  const devices = [
    { deviceId: "z2m-heater_mech_room", name: "heater_mech_room", attributes: { area: "Mech Room" } },
  ];

  function mockAuth(response) {
    return { authedFetch: vi.fn().mockResolvedValue(response) };
  }

  it("shows a device's area and name resolved from the devices list, not the bare deviceId", async () => {
    const auth = mockAuth({ ok: true, json: async () => [
      { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "OPEN",
        detectedAt: "2026-09-01T00:00:00Z", evidence: { currentPowerWatts: 42, continuousHours: 60 } },
    ] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText("Mech Room · heater_mech_room")).toBeTruthy();
    expect(screen.getByText(/Drawing ~42W continuously for 60h/)).toBeTruthy();
  });

  it("falls back to the bare deviceId when no matching device is found", async () => {
    const auth = mockAuth({ ok: true, json: async () => [
      { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-unknown", status: "OPEN",
        detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
    ] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText("z2m-unknown")).toBeTruthy();
  });

  it("shows the empty state when there are no open opportunities", async () => {
    const auth = mockAuth({ ok: true, json: async () => [] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText("No open opportunities — the cabin looks good.")).toBeTruthy();
  });

  it("excludes resolved opportunities from the visible list", async () => {
    const auth = mockAuth({ ok: true, json: async () => [
      { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "RESOLVED",
        detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
    ] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText("No open opportunities — the cabin looks good.")).toBeTruthy();
    expect(screen.queryByText("Mech Room · heater_mech_room")).toBeFalsy();
  });

  it("shows an admin-required message rather than a raw error on 403", async () => {
    const auth = mockAuth({ ok: false, status: 403, json: async () => ({ error: "forbidden" }) });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText(/Admin access required/)).toBeTruthy();
  });

  it("an OPEN row offers both Acknowledge and Resolve; an ACKNOWLEDGED row offers only Resolve", async () => {
    const auth = mockAuth({ ok: true, json: async () => [
      { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "OPEN",
        detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
    ] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);

    expect(await screen.findByText("Acknowledge")).toBeTruthy();
    expect(screen.getByText("Resolve")).toBeTruthy();
  });

  it("acknowledging PATCHes the right id and status, then refreshes", async () => {
    const auth = {
      authedFetch: vi.fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [
          { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "OPEN",
            detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
        ] })
        .mockResolvedValueOnce({ ok: true, json: async () => ({}) })
        .mockResolvedValueOnce({ ok: true, json: async () => [
          { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "ACKNOWLEDGED",
            detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
        ] }),
    };

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);
    await screen.findByText("Acknowledge");
    fireEvent.click(screen.getByText("Acknowledge"));

    expect(await screen.findByText(/acknowledged/)).toBeTruthy();
    const patchCall = auth.authedFetch.mock.calls[1];
    expect(patchCall[0]).toContain("/api/opportunities/op-1/status");
    expect(patchCall[1].method).toBe("PATCH");
    expect(JSON.parse(patchCall[1].body)).toEqual({ status: "ACKNOWLEDGED" });
  });

  it("a failed status update shows an inline error instead of crashing", async () => {
    const auth = {
      authedFetch: vi.fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [
          { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "OPEN",
            detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
        ] })
        .mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({ error: "boom" }) }),
    };

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);
    await screen.findByText("Resolve");
    fireEvent.click(screen.getByText("Resolve"));

    expect(await screen.findByText("boom")).toBeTruthy();
  });

  it("shows type filter chips only when more than one type is present among open opportunities", async () => {
    const auth = mockAuth({ ok: true, json: async () => [
      { id: "op-1", opportunityType: "POWER_DRAW_ANOMALY", deviceId: "z2m-heater_mech_room", status: "OPEN",
        detectedAt: "2026-09-01T00:00:00Z", evidence: {} },
    ] });

    render(<OptimizationOpportunitiesCard auth={auth} devices={devices} />);
    await screen.findByText("heater_mech_room", { exact: false });

    expect(screen.queryByText("All")).toBeFalsy();
  });
});

// Covers the 2026-08-08 presence-toggle finding: the map-pin toolbar
// widget read as "your detected location" but was purely manual, with
// nothing real behind it despite driving real security-severity
// decisions (AutomationRuleService, backend). formatPresenceSignals
// builds the live-detection tooltip from real per-person, per-location
// signals -- N people x M locations by design, not Nate-at-cabin-only.
// See PresenceToggle's own comment and PresenceService.java (backend).
describe("formatPresenceSignals", () => {
  it("names who is present and where, for one signal", () => {
    expect(formatPresenceSignals([{ personId: "nate", location: "cabin", present: true }]))
      .toBe("nate at cabin");
  });

  it("names multiple people across different locations", () => {
    expect(formatPresenceSignals([
      { personId: "nate", location: "cabin", present: true },
      { personId: "emma", location: "home", present: true },
    ])).toBe("nate at cabin, emma at home");
  });

  it("excludes people whose signal is currently not-present", () => {
    expect(formatPresenceSignals([
      { personId: "nate", location: "cabin", present: true },
      { personId: "emma", location: "home", present: false },
    ])).toBe("nate at cabin");
  });

  it("reports nobody present without throwing on an empty or all-absent list", () => {
    expect(formatPresenceSignals([])).toBe("No one currently detected present");
    expect(formatPresenceSignals([{ personId: "nate", location: "cabin", present: false }]))
      .toBe("No one currently detected present");
    expect(formatPresenceSignals(undefined)).toBe("No one currently detected present");
  });
});

// Covers the 2026-08-08 armed-state finding: cabin/security/armed_away
// is a real, live, HA-published MQTT signal that cabin-backend never
// subscribed to. formatArmedTitle must never let "no signal yet" read
// as "disarmed" -- those mean very different things to someone looking
// at an ambiguous alert. See SecurityBadge's own comment in App.jsx.
describe("formatArmedTitle", () => {
  it("reports armed with a timestamp", () => {
    const title = formatArmedTitle({ armed: true, lastUpdated: "2026-08-08T04:00:00Z" });
    expect(title).toMatch(/^Armed \(as of /);
  });

  it("reports disarmed with a timestamp", () => {
    const title = formatArmedTitle({ armed: false, lastUpdated: "2026-08-08T04:00:00Z" });
    expect(title).toMatch(/^Disarmed \(as of /);
  });

  it("never reads as disarmed when no signal has ever been received", () => {
    const title = formatArmedTitle(null);
    expect(title).not.toMatch(/^Disarmed/);
    expect(title).not.toMatch(/^Armed/);
    expect(title).toMatch(/no armed\/disarmed signal/i);
  });

  it("handles undefined the same as null without throwing", () => {
    expect(() => formatArmedTitle(undefined)).not.toThrow();
  });
});

// Covers the 2026-08-08 Grafana-iframe replacement: three separate fix
// attempts failed (the real blocker turned out to be a completely
// different bug -- see docs/ontology.yaml's cabin_grafana_public_access),
// so the embed was replaced with native camera-fps tiles sourced
// directly from Prometheus (FrigateMetricsController, backend) plus a
// link out to the full Grafana dashboard. cameraHealthLabel must never
// let "no data yet" (Prometheus unreachable, fps field absent) read as
// "camera confirmed down" -- those mean very different things.
describe("cameraHealthLabel", () => {
  it("reports fps for a healthy camera", () => {
    expect(cameraHealthLabel(5.1)).toEqual({ label: "5.1 fps", className: "camera-health-ok" });
  });

  it("reports no signal for a camera reporting exactly zero fps", () => {
    expect(cameraHealthLabel(0)).toEqual({ label: "No signal", className: "camera-health-down" });
  });

  it("reports unknown rather than down when fps data is simply absent", () => {
    expect(cameraHealthLabel(null)).toEqual({ label: "Unknown", className: "camera-health-unknown" });
    expect(cameraHealthLabel(undefined)).toEqual({ label: "Unknown", className: "camera-health-unknown" });
  });
});

// Sprint 2's native replacement for the Open WebUI interim UI -- talks to
// POST /api/helpdesk/ask, which does its own retrieval + Ollama generation
// server-side, so this panel is display-only. D5 (docs/ontology/DECISIONS.md)
// requires each answer's source provenance be visible, not just used
// internally -- covered below.
describe("HelpdeskPanel", () => {
  afterEach(() => cleanup());

  function renderPanel() {
    return render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <HelpdeskPanel />
      </AppContext.Provider>
    );
  }

  it("shows the empty state before any question is asked", () => {
    renderPanel();
    expect(screen.getByText(/No questions yet/)).toBeTruthy();
  });

  // Found 2026-09-04 (direct user report): every question 401'd since
  // /api/helpdesk/** was gated (WSJF #8, 2026-09-03) because this panel
  // used a plain fetch() with no Authorization header at all -- not a
  // regression from this session's CabinSession work, just never wired.
  it("asks through auth.authedFetch when an auth prop is provided, not the global fetch", async () => {
    const authedFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ answer: "Answered via authedFetch.", sources: [] }),
    });
    const globalFetch = vi.fn();
    vi.stubGlobal("fetch", globalFetch);
    render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <HelpdeskPanel auth={{ signedIn: true, authedFetch }} />
      </AppContext.Provider>
    );

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "Is the CO sensor online?" } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(await screen.findByText("Answered via authedFetch.")).toBeTruthy();
    expect(authedFetch).toHaveBeenCalledWith("http://cabin-hub:8090/api/helpdesk/ask", expect.objectContaining({ method: "POST" }));
    expect(globalFetch).not.toHaveBeenCalled();
  });

  it("shows a sign-in prompt instead of the chat UI when auth is provided but not signed in", () => {
    render(
      <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
        <HelpdeskPanel auth={{ signedIn: false, signIn: () => {} }} />
      </AppContext.Provider>
    );

    expect(screen.getByText(/Sign in to ask Tiny Helpdesk a question/)).toBeTruthy();
    expect(screen.getByText("Sign in with Google")).toBeTruthy();
    expect(screen.queryByPlaceholderText("Ask a question…")).toBeFalsy();
  });

  it("submits the question, renders the answer, and shows a Verified badge for a manually_curated source", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        question: "What happens during a freeze?",
        answer: "The main water valve shuts off automatically.",
        sources: [{
          entityRef: "z2m-main_water_valve", chunkType: "TROUBLESHOOTING",
          content: "freeze detail", source: "MANUALLY_CURATED", generatedAt: "2026-08-30T00:00:00Z",
        }],
        answeredByModel: true,
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPanel();

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "What happens during a freeze?" } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(fetchMock).toHaveBeenCalledWith("http://cabin-hub:8090/api/helpdesk/ask", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ question: "What happens during a freeze?" }),
    }));
    expect(await screen.findByText("The main water valve shuts off automatically.")).toBeTruthy();
    expect(await screen.findByText(/✓ Verified/)).toBeTruthy();
    expect(screen.getByText(/z2m-main_water_valve/)).toBeTruthy();
  });

  it("shows Auto-generated (not Verified) for an auto_generated source", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        answer: "It's a SONOFF temperature sensor.",
        sources: [{ entityRef: "z2m-temp_kitchen", chunkType: "DESCRIPTION", content: "x", source: "AUTO_GENERATED" }],
        answeredByModel: true,
      }),
    }));
    renderPanel();

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "What is the kitchen sensor?" } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(await screen.findByText(/Auto-generated/)).toBeTruthy();
    expect(screen.queryByText(/✓ Verified/)).toBeNull();
  });

  it("shows the fallback note when Ollama wasn't reachable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        answer: "raw retrieved facts",
        sources: [{ entityRef: "z2m-temp_kitchen", chunkType: "DESCRIPTION", content: "x", source: "AUTO_GENERATED" }],
        answeredByModel: false,
      }),
    }));
    renderPanel();

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "anything" } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(await screen.findByText(/wasn't reachable/)).toBeTruthy();
  });

  it("shows an error message when the request fails, without crashing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500, text: async () => "server error" }));
    renderPanel();

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "anything" } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(await screen.findByText("server error")).toBeTruthy();
  });

  it("does not submit an empty or whitespace-only question", () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderPanel();

    fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: /Ask/ }));

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// Sprint 5 WSJF #3 (r7 handover): confirm() wiring + the frontend "Pending
// Import" surface. PlatformImportFlow/PendingImportRow are exported the
// same way DmDeviceRow/DmRemoveView are, for direct testing rather than
// through the full DeviceManagerPanel (which would need candidates/
// previously-exposed/devices endpoints all mocked just to reach this one
// sub-flow).
describe("PlatformImportFlow", () => {
  afterEach(cleanup);

  function mockAuth({ records = [], types = ["TEMPERATURE_SENSOR", "MOTION_SENSOR"], proposalsResponse } = {}) {
    return { authedFetch: vi.fn((url) => {
      if (url.includes("/api/platform-import/records")) {
        return Promise.resolve({ ok: true, json: async () => records });
      }
      if (url.includes("/api/devices/meta/types")) {
        return Promise.resolve({ ok: true, json: async () => ({ types }) });
      }
      if (url.includes("/proposals")) {
        return Promise.resolve(proposalsResponse || { ok: true, json: async () => [] });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    }) };
  }

  it("shows the empty state when there are no pending imports for the selected platform", async () => {
    const auth = mockAuth({ records: [] });

    render(<PlatformImportFlow onBack={() => {}} auth={auth} />);

    expect(await screen.findByText(/No pending SmartThings imports/)).toBeTruthy();
  });

  it("lists only unconfirmed records for the currently selected platform", async () => {
    const auth = mockAuth({ records: [
      { platform: "smartthings", originalId: "1", originalName: "Kitchen Temp", originalLocation: "Kitchen", confirmedEntityId: null },
      { platform: "smartthings", originalId: "2", originalName: "Already Done", originalLocation: "Kitchen", confirmedEntityId: "smartthings-already_done" },
      { platform: "ring", originalId: "3", originalName: "Front Doorbell", originalLocation: "Front", confirmedEntityId: null },
    ] });

    render(<PlatformImportFlow onBack={() => {}} auth={auth} />);

    expect(await screen.findByText("Kitchen Temp")).toBeTruthy();
    expect(screen.queryByText("Already Done")).toBeFalsy();
    expect(screen.queryByText("Front Doorbell")).toBeFalsy();
  });

  it("switching the platform dropdown re-filters the list", async () => {
    const auth = mockAuth({ records: [
      { platform: "smartthings", originalId: "1", originalName: "Kitchen Temp", originalLocation: "Kitchen", confirmedEntityId: null },
      { platform: "ring", originalId: "3", originalName: "Front Doorbell", originalLocation: "Front", confirmedEntityId: null },
    ] });

    render(<PlatformImportFlow onBack={() => {}} auth={auth} />);
    expect(await screen.findByText("Kitchen Temp")).toBeTruthy();

    fireEvent.change(screen.getByRole("combobox", { name: /Platform/i }), { target: { value: "ring" } });

    expect(await screen.findByText("Front Doorbell")).toBeTruthy();
    expect(screen.queryByText("Kitchen Temp")).toBeFalsy();
  });

  it("shows a live-fetch error inline instead of throwing, e.g. no OAuth credential set up yet", async () => {
    const auth = mockAuth({
      records: [],
      proposalsResponse: { ok: false, status: 500, json: async () => ({ error: "No SmartThings OAuth credential in Vaultwarden (smartthings_oauth) -- complete OAuth first" }) },
    });
    render(<PlatformImportFlow onBack={() => {}} auth={auth} />);
    await screen.findByText(/No pending SmartThings imports/);

    fireEvent.click(screen.getByRole("button", { name: /Fetch devices from SmartThings/ }));

    expect(await screen.findByText(/No SmartThings OAuth credential/)).toBeTruthy();
  });

  it("shows an administrator-required message rather than a raw error on 403", async () => {
    const auth = { authedFetch: vi.fn(() => Promise.resolve({ ok: false, status: 403, json: async () => ({}) })) };

    render(<PlatformImportFlow onBack={() => {}} auth={auth} />);

    expect(await screen.findByText("Administrator access required")).toBeTruthy();
  });
});

describe("PendingImportRow", () => {
  afterEach(cleanup);

  const record = { originalId: "1", originalName: "Kitchen Temp", originalLocation: "Kitchen" };
  const deviceTypes = ["TEMPERATURE_SENSOR", "MOTION_SENSOR"];

  it("pre-fills a slugified entity ID and the original name", () => {
    render(<PendingImportRow record={record} platform="smartthings" deviceTypes={deviceTypes}
      expanded={true} onToggle={() => {}} onConfirmed={() => {}} doFetch={vi.fn()} apiBase="http://cabin" />);

    expect(screen.getByDisplayValue("smartthings-kitchen_temp")).toBeTruthy();
    expect(screen.getByDisplayValue("Kitchen Temp")).toBeTruthy();
  });

  it("does not render the confirm form until expanded", () => {
    render(<PendingImportRow record={record} platform="smartthings" deviceTypes={deviceTypes}
      expanded={false} onToggle={() => {}} onConfirmed={() => {}} doFetch={vi.fn()} apiBase="http://cabin" />);

    expect(screen.queryByLabelText(/Entity ID/)).toBeFalsy();
  });

  it("confirm is disabled until a type is chosen", () => {
    render(<PendingImportRow record={record} platform="smartthings" deviceTypes={deviceTypes}
      expanded={true} onToggle={() => {}} onConfirmed={() => {}} doFetch={vi.fn()} apiBase="http://cabin" />);

    expect(screen.getByRole("button", { name: "Confirm as Device" }).disabled).toBe(true);
  });

  it("submits exactly the confirmed fields and calls onConfirmed on success", async () => {
    const doFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ deviceId: "smartthings-kitchen_temp", deviceLifecycle: "CANDIDATE" }) });
    const onConfirmed = vi.fn();
    render(<PendingImportRow record={record} platform="smartthings" deviceTypes={deviceTypes}
      expanded={true} onToggle={() => {}} onConfirmed={onConfirmed} doFetch={doFetch} apiBase="http://cabin" />);

    fireEvent.change(screen.getByLabelText(/Type/), { target: { value: "TEMPERATURE_SENSOR" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm as Device" }));

    await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
    expect(doFetch).toHaveBeenCalledWith("http://cabin/api/platform-import/smartthings/confirm", expect.objectContaining({ method: "POST" }));
    const body = JSON.parse(doFetch.mock.calls[0][1].body);
    expect(body).toEqual({ originalId: "1", entityId: "smartthings-kitchen_temp", name: "Kitchen Temp", type: "TEMPERATURE_SENSOR", location: "cabin" });
  });

  it("shows the server's error inline on a 409 conflict instead of throwing, e.g. already confirmed", async () => {
    const doFetch = vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ error: "Already confirmed", entityId: "smartthings-kitchen_temp" }) });
    render(<PendingImportRow record={record} platform="smartthings" deviceTypes={deviceTypes}
      expanded={true} onToggle={() => {}} onConfirmed={() => {}} doFetch={doFetch} apiBase="http://cabin" />);

    fireEvent.change(screen.getByLabelText(/Type/), { target: { value: "TEMPERATURE_SENSOR" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm as Device" }));

    expect(await screen.findByText("Already confirmed")).toBeTruthy();
  });
});

// Security & Presence topic: per-day motion history from
// GET /api/presence/activity (OCCUPANCY_SENSOR_ACTIVATED/CLEARED edges).
describe("formatActiveTime / formatDaysSince / formatPresenceDay", () => {
  it("formats active time in whole minutes, then hours", () => {
    expect(formatActiveTime(0)).toBe("0 min");
    expect(formatActiveTime(0.4)).toBe("<1 min");
    expect(formatActiveTime(12.4)).toBe("12 min");
    expect(formatActiveTime(60)).toBe("1 h");
    expect(formatActiveTime(125)).toBe("2 h 5 min");
  });

  it("phrases days since last activity", () => {
    expect(formatDaysSince(0)).toBe("today");
    expect(formatDaysSince(1)).toBe("yesterday");
    expect(formatDaysSince(45)).toBe("45 days ago");
  });

  it("shows the server's calendar date whatever the browser zone is", () => {
    expect(formatPresenceDay("2026-09-14")).toBe("Mon, Sep 14");
    expect(formatPresenceDay("2026-09-01")).toBe("Tue, Sep 1");
  });
});

describe("PresenceActivityView", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const hours = Array(24).fill(0);
  hours[9] = 2; hours[10] = 1;
  const payload = (over = {}) => ({
    generatedAt: "2026-09-15T18:00:00Z", timezone: "America/Chicago", days: 3, visitGapMinutes: 30,
    sensors: [{
      deviceId: "z2m-motion_entry", name: "Entry Motion", location: "cabin", battery: 87,
      lastSeen: "2026-09-15T17:59:00Z", lastActivation: "2026-09-15T15:00:00Z", daysSinceLastActivity: 0,
      totals: { activations: 3, visits: 2, activeMinutes: 4, activeDays: 2, days: 3 },
      byDay: [
        { date: "2026-09-13", activations: 0, visits: 0, activeMinutes: 0, firstAt: null, lastAt: null },
        { date: "2026-09-14", activations: 2, visits: 1, activeMinutes: 3, firstAt: "2026-09-14T14:00:00Z", lastAt: "2026-09-14T14:05:00Z" },
        { date: "2026-09-15", activations: 1, visits: 1, activeMinutes: 1, firstAt: "2026-09-15T15:00:00Z", lastAt: "2026-09-15T15:00:00Z" },
      ],
      byHour: hours, recent: ["2026-09-15T15:00:00Z"],
    }],
    ...over,
  });
  const ok = (body) => vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body });

  it("shows the sensor's totals, last activity and battery", async () => {
    const { container } = render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload())} />);

    await screen.findByText("Entry Motion");
    const stats = container.querySelector(".presence-stats").textContent;
    expect(stats).toContain("Visits2");
    expect(stats).toContain("Activations3");
    expect(stats).toContain("Active time4 min");
    expect(stats).toContain("Days with activity2 of 3");
    expect(screen.getByText(/Last activity today, 10:00\sAM · 87% battery/)).toBeTruthy();
  });

  it("draws one bar per day, zero days included, with the day's detail in the sensor's local time", async () => {
    const { container } = render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload())} />);
    await screen.findByText("Entry Motion");

    const slots = container.querySelectorAll(".presence-bar-slot");
    expect(slots).toHaveLength(3);
    expect(slots[0].title).toBe("Sun, Sep 13: no activity");
    expect(slots[1].title).toMatch(/^Mon, Sep 14: 1 visit · 2 activations · 3 min · 9:00\sAM–9:05\sAM$/);
    expect(container.querySelectorAll(".presence-bar.zero")).toHaveLength(1);
  });

  it("switching the metric rescales the bars", async () => {
    const { container } = render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload())} />);
    await screen.findByText("Entry Motion");
    const bars = () => container.querySelectorAll(".presence-bar");
    expect(parseFloat(bars()[1].style.height)).toBeCloseTo(100);
    expect(parseFloat(bars()[2].style.height)).toBeCloseTo(100);

    fireEvent.click(screen.getByRole("button", { name: "Active time" }));

    expect(screen.getByRole("button", { name: "Active time" }).getAttribute("aria-pressed")).toBe("true");
    expect(parseFloat(bars()[1].style.height)).toBeCloseTo(100);
    expect(parseFloat(bars()[2].style.height)).toBeCloseTo(33.33, 1);
  });

  it("lights the busiest hour fully and leaves empty hours nearly transparent", async () => {
    const { container } = render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload())} />);
    await screen.findByText("Entry Motion");

    const cells = container.querySelectorAll(".presence-hour");
    expect(cells).toHaveLength(24);
    expect(parseFloat(cells[9].style.opacity)).toBeCloseTo(1);
    expect(parseFloat(cells[3].style.opacity)).toBeLessThan(0.1);
    expect(cells[9].title).toBe("9 AM: 2 activations");
  });

  it("re-fetches when the range changes", async () => {
    const fetchMock = ok(payload());
    render(<PresenceActivityView apiBase="http://cabin" authedFetch={fetchMock} />);
    await screen.findByText("Entry Motion");
    expect(fetchMock).toHaveBeenLastCalledWith("http://cabin/api/presence/activity?days=30");

    fireEvent.change(screen.getByLabelText("Range"), { target: { value: "7" } });

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith("http://cabin/api/presence/activity?days=7"));
  });

  it("lists every day in the day-by-day table, newest first", async () => {
    const { container } = render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload())} />);
    await screen.findByText("Entry Motion");

    const rows = container.querySelectorAll(".presence-day-table tbody tr");
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain("Tue, Sep 15");
    expect(rows[2].textContent).toContain("Sun, Sep 13");
    expect(rows[2].textContent).toContain("—");
  });

  it.each([401, 403])("explains the restriction instead of an empty chart on %i", async (status) => {
    const authedFetch = vi.fn().mockResolvedValue({ ok: false, status, json: async () => ({}) });
    render(<PresenceActivityView apiBase="http://cabin" authedFetch={authedFetch} />);

    expect(await screen.findByText(/limited to signed-in adult household members/)).toBeTruthy();
    expect(screen.queryByText("Entry Motion")).toBeNull();
  });

  it("says so when the request fails", async () => {
    render(<PresenceActivityView apiBase="http://cabin" authedFetch={vi.fn().mockRejectedValue(new Error("down"))} />);

    expect(await screen.findByText(/Could not load motion history/)).toBeTruthy();
  });

  it("says so when no sensor has any recorded activity", async () => {
    render(<PresenceActivityView apiBase="http://cabin" authedFetch={ok(payload({ sensors: [] }))} />);

    expect(await screen.findByText("No motion history recorded yet.")).toBeTruthy();
  });

  it("is what the Security & Presence topic tab of Sensor History shows", async () => {
    const devices = [{ deviceId: "z2m-humid_mech", name: "Mech Room", type: "TEMPERATURE_SENSOR", state: "ONLINE",
      location: "cabin", attributes: { enabled: true, reportsFields: ["humidity"] } }];
    const authedFetch = vi.fn((url) => {
      if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, status: 200, json: async () => ({ "z2m-humid_mech": ["humidity"] }) });
      if (url.includes("/api/presence/activity")) return Promise.resolve({ ok: true, status: 200, json: async () => payload() });
      return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    });
    render(<SensorHistoryPanel devices={devices} apiBase="http://cabin" tempUnit="F" authedFetch={authedFetch} />);

    fireEvent.click(await screen.findByRole("tab", { name: "Security & Presence" }));

    expect(await screen.findByText("Entry Motion")).toBeTruthy();
    expect(authedFetch).toHaveBeenCalledWith("http://cabin/api/presence/activity?days=30");
  });
});

// One backend can serve several locations' sensors, so a location's Monitoring
// view asks for only its own.
describe("PresenceActivityView location scope", () => {
  afterEach(() => cleanup());
  const empty = { generatedAt: "2026-09-19T18:00:00Z", timezone: "America/Chicago", days: 30, visitGapMinutes: 30, sensors: [] };
  const ok = () => vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => empty });

  it("adds the location to the request when it has one", async () => {
    const fetchMock = ok();
    render(<PresenceActivityView apiBase="http://cabin" location="home" authedFetch={fetchMock} />);

    await screen.findByText("No motion history recorded yet.");
    expect(fetchMock).toHaveBeenLastCalledWith("http://cabin/api/presence/activity?days=30&location=home");
  });

  it("keeps the location on a range change", async () => {
    const fetchMock = ok();
    render(<PresenceActivityView apiBase="http://cabin" location="cabin" authedFetch={fetchMock} />);
    await screen.findByText("No motion history recorded yet.");

    fireEvent.change(screen.getByLabelText("Range"), { target: { value: "7" } });

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith("http://cabin/api/presence/activity?days=7&location=cabin"));
  });

  it("is passed down from Sensor History's location", async () => {
    const devices = [{ deviceId: "z2m-humid_mech", name: "Mech Room", type: "TEMPERATURE_SENSOR", state: "ONLINE",
      location: "cabin", attributes: { enabled: true, reportsFields: ["humidity"] } }];
    const authedFetch = vi.fn((url) => {
      if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, status: 200, json: async () => ({ "z2m-humid_mech": ["humidity"] }) });
      if (url.includes("/api/presence/activity")) return Promise.resolve({ ok: true, status: 200, json: async () => empty });
      return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    });
    render(<SensorHistoryPanel devices={devices} apiBase="http://cabin" location="cabin" tempUnit="F" authedFetch={authedFetch} />);

    fireEvent.click(await screen.findByRole("tab", { name: "Security & Presence" }));

    await waitFor(() => expect(authedFetch).toHaveBeenCalledWith("http://cabin/api/presence/activity?days=30&location=cabin"));
  });
});

// Alert History only lists what is active right now; the closest thing to a
// history screen is Status Checks (Rules & Alerts), so the tab points there.
describe("SensorHistoryPanel Alert History link", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const devices = [{ deviceId: "z2m-humid_mech", name: "Mech Room", type: "TEMPERATURE_SENSOR", state: "ONLINE",
    location: "cabin", attributes: { enabled: true, reportsFields: ["humidity"] } }];
  const stubFetch = () => vi.stubGlobal("fetch", vi.fn((url) => {
    if (url.includes("/reported-fields")) return Promise.resolve({ ok: true, json: async () => ({ "z2m-humid_mech": ["humidity"] }) });
    if (url.includes("/alerts/active")) return Promise.resolve({ ok: true, json: async () => ({ alerts: [] }) });
    return Promise.resolve({ ok: true, json: async () => ({}) });
  }));

  it("sends you to Rules & Alerts, where Status Checks lives, and says what that covers", async () => {
    stubFetch();
    const setActivePanel = vi.fn();
    render(
      <AppContext.Provider value={{ setActivePanel }}>
        <SensorHistoryPanel devices={devices} apiBase="http://cabin" tempUnit="F" />
      </AppContext.Provider>
    );
    fireEvent.click(await screen.findByRole("tab", { name: /alert history/i }));

    expect(await screen.findByText(/last 24 hours of alerts and automation actions/i)).toBeTruthy();
    expect(screen.getByText(/full historical log is not built yet/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /open status checks/i }));

    expect(setActivePanel).toHaveBeenCalledWith("RULES_ENGINE");
  });

  it("shows no link where there is no app shell to navigate", async () => {
    stubFetch();
    render(<SensorHistoryPanel devices={devices} apiBase="http://cabin" tempUnit="F" />);
    fireEvent.click(await screen.findByRole("tab", { name: /alert history/i }));

    await screen.findByText(/full historical log is not built yet/i);
    expect(screen.queryByRole("button", { name: /open status checks/i })).toBeNull();
  });
});

// A control that is open or on carries the theme's selection glow, so the open
// History toggle has to say it is open (class for the glow, aria for readers).
describe("workflow History toggle shows when it is open", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("is not marked open until pressed, and is marked open while the list shows", async () => {
    render(<WorkflowRulesCard workflows={[
      { workflowId: "wf-1", name: "Leak shutoff", location: "cabin", enabled: true,
        triggerDeviceId: "z2m-leak_mech_room", actions: [{ targetDeviceId: "z2m-main_water_valve" }] },
    ]} />);
    const toggle = screen.getByRole("button", { name: "History" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(toggle.className).not.toContain("btn-ghost-active");

    fireEvent.click(toggle);

    const open = await screen.findByRole("button", { name: "Hide history" });
    expect(open.getAttribute("aria-expanded")).toBe("true");
    expect(open.className).toContain("btn-ghost-active");
  });
});

// Config > Platform lists everything versioned (platform-specs.yaml, served by
// GET /api/system/platform-info as `specs`), not just five live integrations.
describe("FamilyConfigPanel — Platform specs", () => {
  afterEach(cleanup);

  const specs = {
    total: 6,
    counts: { pinned: 2, series: 1, floating: 2, unmanaged: 1 },
    groups: [
      { id: "runtimes", label: "Languages, runtimes and base images", items: [
        { id: "rt.java", name: "Java", declared: "21", track: "series", running: "21.0.11+10-LTS", liveProbe: true, locked: null, sources: [], note: "Compiler level." },
        { id: "rt.node", name: "Node.js", declared: "20-alpine", track: "series", running: null, liveProbe: false, locked: null, sources: [], note: "Node 20 reached end of life in April 2026." },
      ] },
      { id: "services", label: "Services (containers)", items: [
        { id: "svc.kafka", name: "Apache Kafka (Confluent Platform)", declared: "7.6.1", track: "pinned", running: null, liveProbe: false, locked: null, sources: [], note: null },
        { id: "svc.node-red", name: "Node-RED", declared: "latest", track: "floating", running: null, liveProbe: false, locked: null, sources: [], note: null },
        { id: "svc.home-assistant", name: "Home Assistant", declared: "stable", track: "floating", running: null, liveProbe: true, locked: null, sources: [], note: null },
      ] },
      { id: "frontend-libs", label: "UI libraries (npm)", items: [
        { id: "fe.react", name: "React", declared: "latest", track: "floating", running: null, liveProbe: false, locked: "19.2.8", sources: [], note: null },
      ] },
      { id: "host", label: "Host and tooling (not pinned in Git)", items: [
        { id: "host.docker", name: "Docker Engine", declared: "not pinned in Git", track: "unmanaged", running: null, liveProbe: false, locked: null, sources: [], note: "Check with: docker version." },
      ] },
    ],
  };

  function renderPanel(body) {
    const authedFetch = vi.fn((url) => url.includes("/api/system/platform-info")
      ? Promise.resolve({ ok: true, json: async () => body })
      : Promise.resolve({ ok: true, json: async () => [] }));
    return render(
      <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
        <FamilyConfigPanel auth={{ authedFetch }} />
      </AppContext.Provider>
    );
  }
  const withSpecs = { versions: {}, specs, hardware: [], aiDisclosure: null };

  it("lists the backend's languages, runtimes, services and host tooling under their own headings", async () => {
    renderPanel(withSpecs);

    expect(await screen.findByText("Apache Kafka (Confluent Platform)")).toBeTruthy();
    for (const heading of ["Languages, runtimes and base images", "Services (containers)", "UI libraries (npm)", "Host and tooling (not pinned in Git)"]) {
      expect(screen.getByText(heading)).toBeTruthy();
    }
    expect(screen.getByText("Node.js")).toBeTruthy();
    expect(screen.getByText("Node-RED")).toBeTruthy();
    expect(screen.getByText("Docker Engine")).toBeTruthy();
  });

  it("shows the running version when the backend could ask, with what is declared beside it, and says so when it could not", async () => {
    renderPanel(withSpecs);
    await screen.findByText("Java");

    const java = screen.getByText("Java").closest("tr");
    expect(within(java).getByText("21.0.11+10-LTS")).toBeTruthy();
    expect(within(java).getByText("declared 21")).toBeTruthy();
    // Asked (a probe exists) and got nothing back:
    expect(within(screen.getByText("Home Assistant").closest("tr")).getByText("not reachable now")).toBeTruthy();
    // Nothing to ask: just what Git declares, no "not reachable" claim.
    const kafka = screen.getByText("Apache Kafka (Confluent Platform)").closest("tr");
    expect(within(kafka).getByText("7.6.1")).toBeTruthy();
    expect(within(kafka).queryByText(/not reachable/)).toBeNull();
  });

  it("flags what may need maintenance: floating tags, floating series and host software, but not pinned versions", async () => {
    renderPanel(withSpecs);
    await screen.findByText("Node-RED");

    expect(within(screen.getByText("Node-RED").closest("tr")).getByText("floats")).toBeTruthy();
    expect(within(screen.getByText("Node.js").closest("tr")).getByText("patches float")).toBeTruthy();
    expect(within(screen.getByText("Docker Engine").closest("tr")).getByText("not in Git")).toBeTruthy();
    const kafka = screen.getByText("Apache Kafka (Confluent Platform)").closest("tr");
    expect(kafka.querySelector(".meta-chip")).toBeNull();
  });

  it("shows what package-lock.json locks next to a floating npm range", async () => {
    renderPanel(withSpecs);
    const react = (await screen.findByText("React")).closest("tr");

    expect(within(react).getByText("latest")).toBeTruthy();
    expect(within(react).getByText("locked at 19.2.8")).toBeTruthy();
  });

  it("carries the maintenance note under the component name", async () => {
    renderPanel(withSpecs);
    expect(await screen.findByText(/Node 20 reached end of life in April 2026/)).toBeTruthy();
  });

  it("summarises how many components there are and how many float, highlighting the floating count", async () => {
    const { container } = renderPanel(withSpecs);
    await screen.findByText("Node-RED");

    const summary = container.querySelector(".spec-summary");
    expect(summary.textContent).toContain("6 components");
    expect(summary.textContent).toContain("2 pinned");
    expect(summary.textContent).toContain("1 patches float");
    expect(summary.textContent).toContain("2 float on latest");
    expect(summary.textContent).toContain("1 not pinned in Git");
    expect(within(summary).getByText("2 float on latest").className).toContain("spec-chip-warn");
  });

  it("puts each category in a section that can be collapsed, open by default", async () => {
    const { container } = renderPanel(withSpecs);
    await screen.findByText("Node-RED");

    const groups = container.querySelectorAll("details.spec-group");
    expect(groups).toHaveLength(4);
    groups.forEach(g => expect(g.hasAttribute("open")).toBe(true));
  });

  it("still shows the five live versions when the backend predates the full specs", async () => {
    renderPanel({ versions: { homeAssistant: "2026.9.1", ollama: "0.3.12" }, hardware: [], aiDisclosure: null });

    expect(await screen.findByText("2026.9.1")).toBeTruthy();
    expect(screen.getByText("Ollama")).toBeTruthy();
    expect(document.querySelector(".spec-summary")).toBeNull();
  });
});
