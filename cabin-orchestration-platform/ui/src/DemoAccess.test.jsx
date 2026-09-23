import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { App, DEMO_BANNER_TEXT, HIDDEN_FOR_DEMO, FamilyConfigPanel, AppContext } from "./App.jsx";
import { ThemeProvider } from "./ThemeProvider.jsx";

// D22 required test 8 (UI half): demo mode shows the banner, renders no
// sign-in control, and renders the camera placeholder card with no <img>
// or <video>. Backend tests 1-7 are in DemoAccessTest.java.
const devices = [
  { deviceId: "camera.front", type: "CAMERA", name: HIDDEN_FOR_DEMO, state: "ONLINE", lastSeen: null, attributes: {}, location: "cabin" },
  { deviceId: "leak.kitchen", type: "WATER_LEAK_SENSOR", name: "Kitchen Leak", state: "ONLINE", lastSeen: "2026-09-23T12:00:00Z", attributes: { water_leak: false }, location: "cabin" },
];

function stubFetch() {
  const fetchMock = vi.fn((url) => {
    const u = String(url);
    const body = u.endsWith("/api/devices") ? devices
      : u.includes("/api/dashboard/config") || u.includes("/api/alerts/active") ? {}
      : [];
    return Promise.resolve({ ok: true, status: 200, json: async () => body });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("Demo Access (/demo/{token})", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows the banner and no sign-in control", async () => {
    stubFetch();
    render(<ThemeProvider><App demoToken="demo-tok" /></ThemeProvider>);
    expect(screen.getByRole("status").textContent).toContain(DEMO_BANNER_TEXT);
    await waitFor(() => expect(screen.getAllByText(/devices$/).length).toBeGreaterThan(0));
    expect(screen.queryByText(/sign in/i)).toBeNull();
  });

  it("sends the demo token as a CabinToken header, never as ?t=", async () => {
    const fetchMock = stubFetch();
    render(<ThemeProvider><App demoToken="demo-tok" /></ThemeProvider>);
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith("/api/devices"))).toBe(true));
    const [url, opts] = fetchMock.mock.calls.find(([u]) => String(u).endsWith("/api/devices"));
    expect(opts.headers.Authorization).toBe("CabinToken demo-tok");
    fetchMock.mock.calls.forEach(([u]) => expect(String(u)).not.toMatch(/[?&]t=/));
  });

  it("renders the camera placeholder with no <img> or <video>", async () => {
    stubFetch();
    const { container } = render(<ThemeProvider><App demoToken="demo-tok" /></ThemeProvider>);
    fireEvent.click(screen.getAllByTitle("Camera Events")[0] ?? screen.getByText("Camera Events"));
    await waitFor(() => expect(container.querySelector(".demo-camera-card")).not.toBeNull());
    const panel = container.querySelector(".panel-area");
    expect(panel.textContent).toContain(HIDDEN_FOR_DEMO);
    expect(panel.querySelector("img")).toBeNull();
    expect(panel.querySelector("video")).toBeNull();
    expect(panel.querySelector(".demo-camera-card .state-badge").textContent).toBe("ONLINE");
  });

  it("hides account and access-link cards in Config for a demo viewer", () => {
    const auth = { demo: true, signedIn: true, authedFetch: vi.fn(() => Promise.resolve({ ok: true, json: async () => ({}) })) };
    render(
      <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
    expect(screen.queryByText("Guest Access")).toBeNull();
    expect(screen.queryByText("Managed Users")).toBeNull();
    expect(screen.queryByText(/Sign in with Google/)).toBeNull();
  });
});
