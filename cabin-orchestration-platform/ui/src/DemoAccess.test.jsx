import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { App, DEMO_BANNER_TEXT, HIDDEN_FOR_DEMO, FamilyConfigPanel, AppContext, DemoLinkSection, shareLinkStatus, formatStopTime } from "./App.jsx";
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

describe("Create a demo link (Config > Guest Access)", () => {
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it("shows the instructions and the exact stop time before creating", () => {
    vi.useFakeTimers({ now: new Date("2026-09-24T15:00:00Z"), toFake: ["Date"] });
    render(<DemoLinkSection doFetch={vi.fn()} apiBase="http://api" />);
    expect(screen.getByText("Demo link for product evaluation")).toBeTruthy();
    expect(screen.getByText(/Any signed-in admin can create and revoke/)).toBeTruthy();
    expect(screen.getByText(/stops working automatically at the time shown below/)).toBeTruthy();
    const preview = screen.getByTestId("demo-stop-preview");
    expect(preview.textContent).toContain(formatStopTime("2026-10-24T15:00:00Z"));

    fireEvent.change(screen.getByLabelText("Demo link lifetime in days"), { target: { value: "7" } });
    expect(screen.getByTestId("demo-stop-preview").textContent).toContain(formatStopTime("2026-10-01T15:00:00Z"));
  });

  it("refuses to create without a whole number of days (a demo link always ends)", () => {
    render(<DemoLinkSection doFetch={vi.fn()} apiBase="http://api" />);
    fireEvent.change(screen.getByLabelText("Demo link label"), { target: { value: "Agent evaluation" } });
    for (const bad of ["", "0", "1.5", "-3"]) {
      fireEvent.change(screen.getByLabelText("Demo link lifetime in days"), { target: { value: bad } });
      expect(screen.getByRole("button", { name: "Create demo link" }).disabled).toBe(true);
      expect(screen.getByTestId("demo-stop-preview").textContent).toContain("always has an end date");
    }
  });

  it("creates a demo-only link and shows the server's stop time with the one-time URL", async () => {
    const doFetch = vi.fn(() => Promise.resolve({ ok: true, json: async () => ({ token: "abc123", expiresAt: "2026-10-08T15:00:00Z" }) }));
    const onCreated = vi.fn();
    render(<DemoLinkSection doFetch={doFetch} apiBase="http://api" onCreated={onCreated} />);
    fireEvent.change(screen.getByLabelText("Demo link label"), { target: { value: "Agent evaluation – Jane Doe" } });
    fireEvent.change(screen.getByLabelText("Demo link lifetime in days"), { target: { value: "14" } });
    fireEvent.click(screen.getByRole("button", { name: "Create demo link" }));

    const stop = await screen.findByTestId("demo-created-stop");
    expect(stop.textContent).toContain(formatStopTime("2026-10-08T15:00:00Z"));
    expect(screen.getByText(`${window.location.origin}/demo/abc123`)).toBeTruthy();
    const [, opts] = doFetch.mock.calls[0];
    expect(JSON.parse(opts.body)).toEqual({ label: "Agent evaluation – Jane Doe", scope: ["demo"], expiresInDays: 14 });
    expect(onCreated).toHaveBeenCalled();
  });

  it("lists each link with its exact stop time, and marks expired and revoked links", () => {
    const now = Date.parse("2026-09-24T15:00:00Z");
    expect(shareLinkStatus({ expiresAt: "2026-10-01T15:00:00Z" }, now))
      .toEqual({ active: true, text: `stops working ${formatStopTime("2026-10-01T15:00:00Z")}` });
    expect(shareLinkStatus({ expiresAt: "2026-09-20T15:00:00Z" }, now))
      .toMatchObject({ active: false, badge: "Expired" });
    expect(shareLinkStatus({ expiresAt: "2026-10-01T15:00:00Z", revokedAt: "2026-09-23T15:00:00Z" }, now))
      .toMatchObject({ active: false, badge: "Revoked" });
  });
});
