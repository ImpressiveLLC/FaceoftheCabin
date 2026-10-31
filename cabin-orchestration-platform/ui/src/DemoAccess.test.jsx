import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { App, DEMO_BANNER_TEXT, HIDDEN_FOR_DEMO, FamilyConfigPanel, AppContext, DemoLinkSection, shareLinkStatus, formatStopTime, GuestAccessCard, AccessActivity, accessRoutePanel, describeLinkUsage } from "./App.jsx";
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
    // Wait for the status badge, not just the card: until the devices load the
    // card renders its empty state (no badge), so waiting on the card alone
    // passed early and then failed on a slower CI machine (found 2026-10-10,
    // when it blocked the cabin-ui deploy of an unrelated change).
    await waitFor(() => expect(container.querySelector(".demo-camera-card .state-badge")).not.toBeNull());
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

describe("Access link usage (Config > Guest Access)", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const link = (over = {}) => ({
    id: "link-1", token: "secret", label: "Agent preview", scope: ["demo"],
    expiresAt: "2099-01-01T00:00:00Z", revokedAt: null, createdBy: "nate@example.com", createdAt: "2026-10-01T00:00:00Z",
    firstUsedAt: null, lastUsedAt: null, useCount: 0, ...over,
  });

  it("maps recorded routes to panel names", () => {
    expect(accessRoutePanel("page_open")).toBe("Opened the link");
    expect(accessRoutePanel("/api/events/telemetry-history")).toBe("Sensor History");
    expect(accessRoutePanel("/api/alerts/active")).toBe("Alerts");
    expect(accessRoutePanel("/api/rules/workflows")).toBe("Rules");
    expect(accessRoutePanel("/api/devices/abc")).toBe("Devices");
    expect(accessRoutePanel("/api/camera/list")).toBe("Other");
  });

  it("describes a link that was never opened and one that was", () => {
    expect(describeLinkUsage(link())).toBe("Never opened");
    expect(describeLinkUsage(link({ lastUsedAt: "2026-10-28T15:00:00Z", useCount: 1 })))
      .toBe(`Last opened ${formatStopTime("2026-10-28T15:00:00Z")} · 1 open`);
    expect(describeLinkUsage(link({ lastUsedAt: "2026-10-28T15:00:00Z", useCount: 4 }))).toContain("4 opens");
  });

  it("shows last opened and the opens count on each Access Links row", async () => {
    const used = link({ id: "used", label: "Insurance agent", lastUsedAt: "2026-10-28T15:00:00Z", useCount: 3 });
    const unused = link({ id: "unused", label: "Contractor" });
    const authedFetch = vi.fn(() => Promise.resolve({ ok: true, status: 200, json: async () => [used, unused] }));
    render(<ThemeProvider><GuestAccessCard auth={{ authedFetch }} /></ThemeProvider>);

    expect(await screen.findByText(/Last opened .* · 3 opens/)).toBeTruthy();
    expect(screen.getByText("Never opened")).toBeTruthy();
  });

  it("lists time, panel and status in the activity expander", async () => {
    const events = [
      { id: 2, occurredAt: "2026-10-28T15:10:00Z", route: "/api/alerts/active", status: 200, hits: 4 },
      { id: 1, occurredAt: "2026-10-28T15:00:00Z", route: "page_open", status: 200, hits: 1 },
    ];
    const doFetch = vi.fn(() => Promise.resolve({ ok: true, status: 200, json: async () => events }));
    const { container } = render(<AccessActivity id="link-1" doFetch={doFetch} apiBase="http://api" />);
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));

    expect(await screen.findByText("Alerts")).toBeTruthy();
    expect(screen.getByText("Opened the link")).toBeTruthy();
    expect(doFetch.mock.calls[0][0]).toBe("http://api/api/access-tokens/link-1/activity?days=30");
  });

  it("says so when the activity cannot be loaded", async () => {
    const doFetch = vi.fn(() => Promise.resolve({ ok: false, status: 403, json: async () => ({}) }));
    const { container } = render(<AccessActivity id="link-1" doFetch={doFetch} apiBase="http://api" />);
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));

    expect(await screen.findByText("Could not load activity.")).toBeTruthy();
  });

  it("the demo page sends one tagged page-open call on mount", async () => {
    const fetchMock = stubFetch();
    render(<ThemeProvider><App demoToken="demo-tok" /></ThemeProvider>);
    await waitFor(() => expect(fetchMock.mock.calls.some(([, o]) => o?.headers?.["X-Demo-Page-Open"] === "1")).toBe(true));
    const tagged = fetchMock.mock.calls.filter(([, o]) => o?.headers?.["X-Demo-Page-Open"] === "1");
    expect(tagged).toHaveLength(1);
    expect(String(tagged[0][0])).toContain("/api/dashboard/config");
    expect(tagged[0][1].headers.Authorization).toBe("CabinToken demo-tok");
  });
});
