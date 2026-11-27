import React from "react";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { render, fireEvent, within, waitFor, cleanup, act } from "@testing-library/react";
import { MonitoringPreferenceEditor, HIDE_CONFIRMATION, reorderedMonitoringCards } from "./MonitoringPreferenceEditor";
import { AppContext, MnSeeView } from "./App.jsx";
import { useMonitoringPreferences } from "./monitoringPreferences";

const card = (deviceId, included = true) => ({ deviceId, included });
const entry = (id, type = "CAMERA", state = "ONLINE") => ({ d: { deviceId: id, type, location: "cabin" },
  tile: { deviceId: id, label: id, state } });
const snapshot = (revision = 1, cards = [card("a"), card("b")]) => ({ location: "cabin", revision, cards });
let fetcher, preferences, auth;
beforeEach(() => {
  fetcher = vi.fn(async () => ({ ok: true, status: 200, json: async () => snapshot(2) }));
  auth = { signedIn: true, authedFetch: fetcher };
  preferences = { data: snapshot(), refresh: vi.fn(), accept: vi.fn() };
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); });
function editor(entries = [entry("a"), entry("b")], session = auth) {
  const view = render(<MonitoringPreferenceEditor entries={entries} location="cabin" apiBase="http://cabin"
    auth={session} preferences={preferences} />);
  fireEvent.click(within(view.container).getByText("Monitoring visibility and order"));
  return view;
}

it.each([
  [{ signedIn: false }, /Sign in to change/],
  [{ signedIn: true, demo: true }, /Demo links are view-only/],
  [{ signedIn: true, guest: true }, /Guest links are view-only/],
  [{ signedIn: true, managedUserRole: "VIEWER" }, /Viewer sessions are view-only/],
])("disables edits with a reason for read-only session %#", (session, reason) => {
  const view = editor(undefined, { ...session, authedFetch: fetcher });
  expect(within(view.container).getByText(reason)).toBeTruthy();
  const button = within(view.container).getByRole("button", { name: "Hide a from Monitoring" });
  expect(button.disabled).toBe(true);
  fireEvent.click(button);
  expect(fetcher).not.toHaveBeenCalled();
});

it.each(["SMOKE_ALARM", "CO_ALARM", "CO_SENSOR", "WATER_LEAK_SENSOR", "WATER_VALVE", "LOCK", "TEMPERATURE_SENSOR", "THERMOSTAT"])(
  "confirms safety effects before hiding %s and cancellation sends nothing", type => {
    window.confirm.mockReturnValue(false);
    const view = editor([entry("a", type)]);
    fireEvent.click(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }));
    expect(window.confirm).toHaveBeenCalledWith(HIDE_CONFIRMATION);
    expect(fetcher).not.toHaveBeenCalled();
  });

it("sends explicit inclusion and expected revision, accepting success only after response", async () => {
  let finish;
  fetcher.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const view = editor();
  const button = within(view.container).getByRole("button", { name: "Hide a from Monitoring" });
  fireEvent.click(button); fireEvent.click(button);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(preferences.accept).not.toHaveBeenCalled();
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ expectedRevision: 1, cards: [card("a", false), card("b")] });
  expect(fetcher.mock.calls[0][1].method).toBe("PATCH");
  const saved = snapshot(2, [card("a", false), card("b")]);
  await act(async () => finish({ ok: true, status: 200, json: async () => saved }));
  expect(preferences.accept).toHaveBeenCalledWith(saved);
  expect(within(view.container).getByText("Preference saved.")).toBeTruthy();
});

it.each([401, 403, 500])("failure %s keeps prior state and reports no save", async status => {
  fetcher.mockResolvedValue({ ok: false, status });
  const view = editor();
  fireEvent.click(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }));
  await waitFor(() => expect(within(view.container).queryByText("Saving shared preferences…")).toBeNull());
  expect(preferences.accept).not.toHaveBeenCalled();
  expect(within(view.container).queryByText("Preference saved.")).toBeNull();
  expect(preferences.data.cards).toEqual([card("a"), card("b")]);
});

it("409 refetches without silently replaying the stale write", async () => {
  fetcher.mockResolvedValue({ ok: false, status: 409 });
  const view = editor();
  fireEvent.click(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }));
  await within(view.container).findByText(/Preferences changed elsewhere/);
  expect(preferences.refresh).toHaveBeenCalledTimes(1);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(preferences.accept).not.toHaveBeenCalled();
});

it("reorder substitutes only movable slots and carries explicit hidden inclusion", async () => {
  preferences.data = snapshot(7, [card("a"), card("alarm"), card("hidden", false), card("b")]);
  const view = editor([entry("a"), entry("alarm", "CAMERA", "ALARM"), entry("hidden"), entry("b")]);
  fireEvent.click(within(view.container).getByRole("button", { name: "Move b up" }));
  fireEvent.click(within(view.container).getByRole("button", { name: "Save order" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ expectedRevision: 7,
    cards: [card("b"), card("alarm"), card("hidden", false), card("a")] });
  expect(() => reorderedMonitoringCards([card("a")], ["a"], ["unknown"])).toThrow();
});

it("active alarms cannot be hidden", () => {
  const view = editor([entry("a", "CO_ALARM", "CRITICAL")]);
  expect(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }).disabled).toBe(true);
  expect(within(view.container).getByText("Active alarm stays visible.")).toBeTruthy();
});

it("an alarm arriving during Hide remains rendered and never claims hidden", async () => {
  let finish;
  let stored = snapshot(1, [card("a")]);
  const server = vi.fn(async (url, options) => {
    if (options?.method === "PATCH") return new Promise(resolve => { finish = resolve; });
    return { ok: true, json: async () => url.includes("monitoring-preferences") ? stored : {} };
  });
  vi.stubGlobal("fetch", server);
  const session = { signedIn: true, authedFetch: server };
  const device = { deviceId: "a", name: "a", type: "TEMPERATURE_SENSOR", location: "cabin", state: "ONLINE", attributes: { temperature: 20 } };
  const renderView = state => <AppContext.Provider value={{ displayConfigs: {} }}>
    <MnSeeView devices={[{ ...device, state }]} activeLocation="cabin" active={false} sharedPreferencesEnabled auth={session} />
  </AppContext.Provider>;
  const view = render(renderView("ONLINE"));
  fireEvent.click(within(view.container).getByText("Monitoring visibility and order"));
  const hide = within(view.container).getByRole("button", { name: "Hide a from Monitoring" });
  await waitFor(() => expect(hide.disabled).toBe(false));
  fireEvent.click(hide);
  view.rerender(renderView("CRITICAL"));
  stored = snapshot(2, [card("a", false)]);
  await act(async () => finish({ ok: true, status: 200, json: async () => stored }));
  expect(view.container.querySelectorAll(".kpi-tile")).toHaveLength(1);
  expect(within(view.container).getByText("Active alarm stays visible.")).toBeTruthy();
  expect(within(view.container).queryByText("Hidden from Monitoring")).toBeNull();
});

it("two readers converge after a conflict/refetch without replaying the write", async () => {
  let stored = snapshot();
  const server = vi.fn(async (_url, options) => {
    if (options?.method === "PATCH") {
      const update = JSON.parse(options.body);
      if (update.expectedRevision !== stored.revision) return { ok: false, status: 409 };
      stored = snapshot(stored.revision + 1, update.cards);
    }
    const value = structuredClone(stored);
    return { ok: true, status: 200, json: async () => value };
  });
  const session = { signedIn: true, authedFetch: server };
  function Reader() {
    const prefs = useMonitoringPreferences(true, "cabin", "http://cabin", server);
    return <MonitoringPreferenceEditor entries={[entry("a"), entry("b")]} location="cabin"
      apiBase="http://cabin" auth={session} preferences={prefs} />;
  }
  const first = render(<Reader />), second = render(<Reader />);
  for (const view of [first, second]) fireEvent.click(within(view.container).getByText("Monitoring visibility and order"));
  await waitFor(() => expect(within(first.container).getByRole("button", { name: "Hide a from Monitoring" }).disabled).toBe(false));
  await waitFor(() => expect(within(second.container).getByRole("button", { name: "Hide a from Monitoring" }).disabled).toBe(false));
  fireEvent.click(within(first.container).getByRole("button", { name: "Hide a from Monitoring" }));
  await within(first.container).findByRole("button", { name: "Show a on Monitoring" });
  fireEvent.click(within(second.container).getByRole("button", { name: "Hide a from Monitoring" }));
  await within(second.container).findByText(/Preferences changed elsewhere/);
  await within(second.container).findByRole("button", { name: "Show a on Monitoring" });
  expect(server.mock.calls.filter(([, options]) => options?.method === "PATCH")).toHaveLength(2);
  expect(stored.revision).toBe(2);
  fireEvent.click(within(first.container).getByRole("button", { name: "Show a on Monitoring" }));
  await within(first.container).findByRole("button", { name: "Hide a from Monitoring" });
  fireEvent.click(within(second.container).getByRole("button", { name: "Refresh shared preferences" }));
  await within(second.container).findByRole("button", { name: "Hide a from Monitoring" });
});

it("network failure leaves prior preferences intact", async () => {
  fetcher.mockRejectedValue(new Error("Network unavailable"));
  const view = editor();
  fireEvent.click(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }));
  await within(view.container).findByText("Network unavailable");
  expect(preferences.accept).not.toHaveBeenCalled();
  expect(preferences.data.revision).toBe(1);
});

it("a late save response after location change cannot update the new view", async () => {
  let finish;
  fetcher.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const view = editor();
  fireEvent.click(within(view.container).getByRole("button", { name: "Hide a from Monitoring" }));
  view.rerender(<MonitoringPreferenceEditor entries={[entry("a")]} location="home" apiBase="http://home"
    auth={auth} preferences={preferences} />);
  await act(async () => finish({ ok: true, status: 200, json: async () => snapshot(2, [card("a", false)]) }));
  expect(preferences.accept).not.toHaveBeenCalled();
});
