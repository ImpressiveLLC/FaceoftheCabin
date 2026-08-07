import React from "react";
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { isCameraEvent, mergeHubLocations, AppContext, FamilyHubPanel } from "./App.jsx";
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
