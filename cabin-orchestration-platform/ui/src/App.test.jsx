import React from "react";
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { isCameraEvent, mergeHubLocations, buildCameraEventsUrl, isLocationDeployed, formatPresenceSignals, AppContext, FamilyHubPanel, FamilyConfigPanel } from "./App.jsx";
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
