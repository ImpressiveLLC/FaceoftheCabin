import React from "react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor, within } from "@testing-library/react";
import { isCameraEvent, mergeHubLocations, buildCameraEventsUrl, cameraEventsWindowLabel, CAMERA_EVENTS_WINDOWS, groupCameraEvents, classifyMediaFetchStatus, isLocationDeployed, formatPresenceSignals, formatArmedTitle, cameraHealthLabel, allLocationsLabel, checkinStatusLabel, groupDevices, filterDeviceManagerDevices, resolveDeviceManagerFilter, buildOrderedDeviceGroups, migrateLegacyDeviceOrder, reorderIds, WORKFLOW_BY_TYPE, deviceLifecycleState, humanizeRuleId, automationAlertSteps, alertLevelFor, deriveNavAlertLevels, AppContext, FamilyHubPanel, FamilyConfigPanel, RulesPanel, DmDeviceDetail, DmEditForm, DmDeviceRow, workflowsForDevice, WorkflowRulesCard, CameraEventsPanel, CameraNotifyToggle, DeviceDiscoveryOverlay, CameraEventClip, kpiTileFor, MnSeeView, countParentDevices, DeviceManagerPanel } from "./App.jsx";
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
    expect(kpiTileFor({ deviceId: "x", type: "MOTION_SENSOR", state: "ONLINE" }, "F")).toBeNull();
  });
});

describe("Monitoring reorder actually reaches the real grid (found 2026-08-25)", () => {
  afterEach(() => { cleanup(); localStorage.clear(); });

  const tempA = { deviceId: "temp-a", name: "Temp A", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin", attributes: { temperature: 20 } };
  const tempB = { deviceId: "temp-b", name: "Temp B", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin", attributes: { temperature: 22 } };
  const lock  = { deviceId: "lock-a", name: "Lock A", type: "LOCK", state: "LOCKED", location: "cabin" };

  it("renders tiles in the saved order, not the old fixed type-bucket order", () => {
    // Saved order deliberately puts the lock before both temp sensors --
    // the previous fixed-bucket rendering always showed every temp sensor
    // before every lock, regardless of any saved preference.
    localStorage.setItem("order.monitoring.cabin", JSON.stringify(["lock-a", "temp-b", "temp-a"]));
    render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, tempB, lock]} activeLocation="cabin" active={false} reorderMode={false} />
      </AppContext.Provider>
    );
    const labels = screen.getAllByText(/^(Temp A|Temp B|Lock A)$/).map(el => el.textContent);
    expect(labels).toEqual(["Lock A", "Temp B", "Temp A"]);
  });

  it("drags the real grid tile itself, not a separate list row, and persists the new order", () => {
    render(
      <AppContext.Provider value={{ displayConfigs: {} }}>
        <MnSeeView devices={[tempA, tempB, lock]} activeLocation="cabin" active={false} reorderMode={true} />
      </AppContext.Provider>
    );
    const tileA = screen.getByText("Temp A").closest(".kpi-tile");
    const tileLock = screen.getByText("Lock A").closest(".kpi-tile");
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
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    renderPanel();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls[0][0]).toContain("window=24h");
    expect(await screen.findByText(/No camera activity in last 24 hours/)).toBeTruthy();
  });

  it("refetches with the newly selected window and updates the empty-state message", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    renderPanel();
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText("Camera events time range"), { target: { value: "240h" } });

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(c => c[0]);
      expect(urls.some(u => u.includes("window=240h"))).toBe(true);
    });
    expect(await screen.findByText(/No camera activity in last 10 days/)).toBeTruthy();
  });

  it("persists the selected window to localStorage and restores it on next mount", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));

    renderPanel();
    fireEvent.change(screen.getByLabelText("Camera events time range"), { target: { value: "72h" } });
    await waitFor(() => expect(localStorage.getItem("cameraEvents.window")).toBe("72h"));
    cleanup();

    const fetchMock2 = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock2);
    renderPanel();

    await waitFor(() => expect(fetchMock2).toHaveBeenCalled());
    expect(fetchMock2.mock.calls[0][0]).toContain("window=72h");
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
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AppContext.Provider value={{ locationCfg: { id: "home", apiBase: "http://home-hub:8080" } }}>
        <CameraEventsPanel auth={mockAuth()} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = fetchMock.mock.calls[0][0];
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
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AppContext.Provider value={{ locationCfg: { id: "cabin", apiBase: "http://cabin-hub:8090" } }}>
        <CameraEventsPanel auth={mockAuth()} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = fetchMock.mock.calls[0][0];
    expect(url.startsWith("http://cabin-hub:8090/api/events?")).toBe(true);
    expect(url).toContain("location=cabin");
  });

  it("applies no location filter when viewing 'both' (locationCfg is null)", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AppContext.Provider value={{ locationCfg: null }}>
        <CameraEventsPanel auth={mockAuth()} />
      </AppContext.Provider>
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = fetchMock.mock.calls[0][0];
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

  it("shows candidates only when Candidates is explicitly selected", () => {
    expect(filterDeviceManagerDevices(devices, "candidates").map(d => d.deviceId)).toEqual(["candidate"]);
  });

  it("keeps cached devices out of the default view until Previously exposed is explicitly selected", () => {
    expect(filterDeviceManagerDevices(devices, "previous").map(d => d.deviceId))
      .toEqual(["deferred", "ignored"]);
  });

  it("the legacy 'all' filter value (no longer a selectable option) still resolves to the same default set", () => {
    expect(filterDeviceManagerDevices(devices, "all").map(d => d.deviceId))
      .toEqual(["assigned", "available", "candidate", "legacy"]);
  });

  it("always reconciles Lifecycle grouping to All active/review devices", () => {
    expect(resolveDeviceManagerFilter("candidate", "in_scope")).toBe("all");
    expect(resolveDeviceManagerFilter("candidate", "candidates")).toBe("all");
    expect(resolveDeviceManagerFilter("workflow", "candidates")).toBe("candidates");
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
    expect(screen.getByText(/cabin.*active/)).toBeTruthy();

    expect(screen.getByText("Draft alert")).toBeTruthy();
    expect(screen.getByText(/no actions/i)).toBeTruthy();
    expect(screen.getByText(/cabin.*draft/)).toBeTruthy();
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

  it("Change mode offers the same Group/Show controls as See", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    expect(screen.getByLabelText(/^group$/i)).toBeTruthy();
    expect(screen.getByLabelText(/^show$/i)).toBeTruthy();
  });

  it("Reset Filters snaps Group/Show back to defaults without deselecting the current device", async () => {
    renderPanel({ devices: twoDevices });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    fireEvent.click(screen.getByText("Device One"));
    // Changing Group remounts DmSeeView (keyed on groupBy) -- flush its
    // re-fired mount-time fetches before continuing.
    let callsBefore = fetch.mock.calls.length;
    fireEvent.change(screen.getByLabelText(/^group$/i), { target: { value: "room" } });
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(callsBefore));
    fireEvent.change(screen.getByLabelText(/^show$/i), { target: { value: "candidates" } });
    expect(screen.getByLabelText(/^show$/i).value).toBe("candidates");

    callsBefore = fetch.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: /reset filters/i }));
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(callsBefore));
    expect(screen.getByLabelText(/^group$/i).value).toBe("type");
    expect(screen.getByLabelText(/^show$/i).value).toBe("in_scope");
    expect(screen.getByText("d1")).toBeTruthy(); // dm-detail-id -- still selected
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
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("uses backend severity directly instead of a browser duration timer", () => {
    expect(alertLevelFor([])).toBeNull();
    expect(alertLevelFor([{ severity: "WARN" }])).toBe("warn");
    expect(alertLevelFor([{ severity: "WARN" }, { severity: "CRITICAL" }])).toBe("critical");
    expect(deriveNavAlertLevels([{ severity: "CRITICAL" }])).toEqual({
      DEVICE_MANAGER: "critical", MONITORING: "critical", RULES_ENGINE: "critical",
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
    expect(screen.getByText(/CABIN_BACKEND · deploy time · read only/)).toBeTruthy();
  });
});

describe("AutomationAlertCard (via RulesPanel)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderWith(activeLocation = "cabin") {
    return render(
      <AppContext.Provider value={{ activeLocation }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("renders the See/Think/Act card from a real CRITICAL AUTOMATION_ALERT event, matching the marketing scenario", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [{
        eventId: "e1", sourceDeviceId: "psi_mech_room", eventType: "AUTOMATION_ALERT",
        severity: "CRITICAL", timestamp: new Date().toISOString(),
        payload: {
          ruleId: "WATER_PRESSURE_LOW",
          see: "Pressure dropped below the safe range.",
          think: "The cabin is away, no fixture is expected to be running, and the mechanical room sensor reports 26.0 PSI.",
          act: "Alert Nate",
          tags: ["CABIN - AWAY", "26.0 PSI", "UNEXPECTED USE"],
        },
      }],
    }));

    renderWith();

    expect(await screen.findByText("Pressure dropped below the safe range.")).toBeTruthy();
    expect(screen.getByText(/mechanical room sensor reports 26.0 PSI/)).toBeTruthy();
    expect(screen.getByText("UNEXPECTED USE")).toBeTruthy();
    expect(screen.getByText("No routine explains it")).toBeTruthy();
    expect(screen.getAllByText("Alert Nate").length).toBeGreaterThan(0);
  });

  it("shows an honest empty state instead of a stale or fabricated alert when there's nothing to report", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));

    renderWith();

    expect(await screen.findByText(/No automation alerts in the last 24 hours/)).toBeTruthy();
  });

  it("degrades gracefully instead of crashing when the events fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));

    renderWith();

    expect(await screen.findByText(/No automation alerts in the last 24 hours/)).toBeTruthy();
  });

  // 2026-08-21: WorkflowRuleService.publishNotification() reuses this
  // card's exact {see,think,act,tags,ruleId} shape for WORKFLOW_ACTION
  // events (docs/ontology.yaml's notify_critical entity), but this card
  // used to only query eventTypePrefix=AUTOMATION_ALERT -- every
  // workflow-engine-driven alert was silently invisible here. Covers the
  // fix: the broadened prefix list actually reaches the fetch call, and a
  // WORKFLOW_ACTION event renders through the same narrative markup.
  it("also surfaces WORKFLOW_ACTION events, not just AUTOMATION_ALERT ones", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [{
        eventId: "e2", sourceDeviceId: "z2m-leak_mech_room", eventType: "WORKFLOW_ACTION",
        severity: "CRITICAL", timestamp: new Date().toISOString(),
        payload: {
          ruleId: "WORKFLOW_wf-leak-shutoff-1", see: "Water leak detected",
          think: "Human-configured workflow 'Leak shutoff' matched this event",
          act: "Shut off main water valve + Notify", tags: ["WORKFLOW"],
        },
      }],
    });
    vi.stubGlobal("fetch", fetchMock);

    renderWith();

    expect(await screen.findByText("Water leak detected")).toBeTruthy();
    // "WORKFLOW" itself renders twice by design here (the humanizeRuleId
    // category badge AND the tags chip both read "WORKFLOW" for this
    // payload) -- assert the category badge specifically rather than an
    // ambiguous bare-text match.
    expect(screen.getByText("Water leak detected").closest(".automation-alert-card")
      .querySelector(".automation-alert-category").textContent).toBe("WORKFLOW");
    // Other RulesPanel siblings (WorkflowRulesCard's RecentExecutionsList,
    // BuiltinRules) also call fetch on mount -- assert by content, not by
    // call order, since effect ordering across sibling components isn't
    // this test's concern.
    expect(fetchMock.mock.calls.some(([url]) =>
      url.includes("eventTypePrefix=AUTOMATION_ALERT,WORKFLOW_ACTION,WORKFLOW_UNCONFIRMED"))).toBe(true);
  });

  it("shows a real recent list (more than just the single latest alert)", async () => {
    const now = Date.now();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        { eventId: "older", sourceDeviceId: "d1", eventType: "AUTOMATION_ALERT", severity: "WARN",
          timestamp: new Date(now - 60_000).toISOString(), payload: { ruleId: "FREEZE_RISK", see: "Older alert" } },
        { eventId: "newer", sourceDeviceId: "d2", eventType: "AUTOMATION_ALERT", severity: "CRITICAL",
          timestamp: new Date(now).toISOString(), payload: { ruleId: "WATER_PRESSURE_LOW", see: "Newer alert" } },
      ],
    }));

    renderWith();

    expect(await screen.findByText("Newer alert")).toBeTruthy();
    expect(screen.getByText("Older alert")).toBeTruthy();
  });

  it("queries both cabin and home when the location switcher is set to Both", async () => {
    const fetchMock = vi.fn((url) => Promise.resolve({
      ok: true,
      json: async () => url.startsWith("http://home-hub:8080")
        ? [{ eventId: "home1", sourceDeviceId: "d3", eventType: "WORKFLOW_ACTION", severity: "WARN",
              timestamp: new Date().toISOString(), payload: { ruleId: "WORKFLOW_wf-home-1", see: "Home alert" } }]
        : [{ eventId: "cabin1", sourceDeviceId: "d4", eventType: "AUTOMATION_ALERT", severity: "WARN",
              timestamp: new Date().toISOString(), payload: { ruleId: "FREEZE_RISK", see: "Cabin alert" } }],
    }));
    vi.stubGlobal("fetch", fetchMock);

    renderWith("both");

    expect(await screen.findByText("Cabin alert")).toBeTruthy();
    expect(screen.getByText("Home alert")).toBeTruthy();
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

  it("displays the configured platform and remote access from real backend config", () => {
    renderPanel({ config: { platformName: "Test Platform", platform: "A test VM", remoteAccess: "Tailscale,WireGuard" } });
    expect(screen.getByText("A test VM")).toBeTruthy();
    expect(screen.getByText("Tailscale, WireGuard")).toBeTruthy();
  });

  it("defaults remote access to Tailscale when config hasn't loaded yet", () => {
    renderPanel({ config: {} });
    expect(screen.getByText("Tailscale")).toBeTruthy();
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
