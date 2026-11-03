import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, cleanup, fireEvent, within, act } from "@testing-library/react";
import { AlertControls, AppContext, RecoveryStatusWidget, formatHeartbeatAge, mergeStatusCheckItems, alertLevelFor } from "./App.jsx";

// Recovery check and component heartbeats: the Monitoring section's detail view, and the proof that the Critical banner
// carries a recovery problem the backend raised through /api/alerts/active (docs/governance/proposals/2026-11-03-recovery-heartbeat-service.md).

const reply = (status, body) => Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) });

const comps = (down = []) => ["backend", "postgres", "kafka"].map(name => ({
  name, status: down.includes(name) ? "down" : "ok", lastOkAt: "2026-11-03T11:59:30Z", ageSeconds: 42, misses: 0,
  downSince: down.includes(name) ? "2026-11-03T11:50:00Z" : null, detail: null,
}));

const view = (over = {}) => ({
  enabled: true, state: "ok", ageSeconds: 20, status: "running", stale: false, intervalSeconds: 60, missLimit: 5,
  notify: { ntfy: "configured", lastResult: "ok" }, deadman: { configured: true, lastResult: "ok" },
  boot: { severity: "info", summary: "Cabin restarted at 2026-11-02T23:54:00Z after an unclean shutdown, recovered in 4 min, telemetry gap 12 min. All 40 checks clear.", uncleanShutdown: "true", problems: [] },
  components: comps(), ...over,
});

describe("formatHeartbeatAge", () => {
  it("reads in the unit a person would say", () => {
    expect(formatHeartbeatAge(null)).toBe("never");
    expect(formatHeartbeatAge(12)).toBe("12 s ago");
    expect(formatHeartbeatAge(300)).toBe("5 min ago");
    expect(formatHeartbeatAge(7200)).toBe("2 h ago");
    expect(formatHeartbeatAge(86400 * 3)).toBe("3 days ago");
  });
});

describe("RecoveryStatusWidget", () => {
  afterEach(cleanup);

  it("lists every component with its heartbeat age and shows the last restart summary", async () => {
    const fetchMock = vi.fn(() => reply(200, view()));
    render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={fetchMock} pollMs={0} />);
    expect(await screen.findByText(/Recovery service reporting — last report 20 s ago/)).toBeTruthy();
    const list = within(screen.getByRole("list", { name: "Component heartbeats" }));
    expect(list.getAllByRole("listitem")).toHaveLength(3);
    expect(list.getByText("kafka")).toBeTruthy();
    expect(list.getAllByText("last heartbeat 42 s ago")).toHaveLength(3);
    expect(screen.getByText(/recovered in 4 min, telemetry gap 12 min/)).toBeTruthy();
    expect(screen.getByText(/The previous shutdown was not clean/)).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledWith("http://hub/api/system/recovery");
  });

  it("names the components that are down", async () => {
    render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={() => reply(200, view({ state: "degraded", components: comps(["kafka"]) }))} pollMs={0} />);
    expect(await screen.findByText("1 component down: kafka")).toBeTruthy();
    expect(screen.getByText("DOWN")).toBeTruthy();
  });

  it("says plainly when the recovery service is not reporting", async () => {
    render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={() => reply(200, view({ state: "silent", ageSeconds: 1800, stale: true, message: "The recovery service has not updated its report for 30 minutes." }))} pollMs={0} />);
    expect(await screen.findByText(/Recovery service NOT REPORTING — last report 30 min ago/)).toBeTruthy();
    expect(screen.getByText(/has not updated its report for 30 minutes/)).toBeTruthy();
  });

  it("says plainly when the service was stopped on purpose", async () => {
    render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={() => reply(200, view({ state: "stopped", status: "stopped" }))} pollMs={0} />);
    expect(await screen.findByText(/STOPPED on purpose — nothing is watching the components/)).toBeTruthy();
  });

  it("shows install commands with a copy button when the service is not installed", async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.assign(navigator, { clipboard: { writeText } });
    render(<RecoveryStatusWidget apiBase="http://hub" pollMs={0}
      authedFetch={() => reply(200, { enabled: true, state: "not-installed", message: "Not installed.", installCommands: "sudo systemctl enable --now cabin-recovery-heartbeat" })} />);
    expect(await screen.findByText("Recovery heartbeat service is not installed")).toBeTruthy();
    expect(screen.getByLabelText("Install commands for the recovery heartbeat service").textContent).toContain("enable --now cabin-recovery-heartbeat");
    fireEvent.click(screen.getByText("Copy commands"));
    expect(await screen.findByText("Copied")).toBeTruthy();
    expect(writeText).toHaveBeenCalledWith("sudo systemctl enable --now cabin-recovery-heartbeat");
  });

  it("warns when nothing can reach a phone, and when no external dead-man check exists", async () => {
    render(<RecoveryStatusWidget apiBase="http://hub" pollMs={0}
      authedFetch={() => reply(200, view({ notify: { ntfy: "not-configured" }, deadman: { configured: false } }))} />);
    expect(await screen.findByText(/Phone alerts are not configured on the host/)).toBeTruthy();
    expect(screen.getByText(/No external dead-man check is configured/)).toBeTruthy();
  });

  it("renders nothing when the feature is disabled, and stays quiet for non-administrators", async () => {
    const { container, unmount } = render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={() => reply(200, { enabled: false, state: "disabled" })} pollMs={0} />);
    await act(async () => { await new Promise(r => setTimeout(r, 20)); });
    expect(container.textContent).toBe("");
    unmount();
    const forbidden = render(<RecoveryStatusWidget apiBase="http://hub" authedFetch={() => reply(403, {})} pollMs={0} quietWhenForbidden />);
    await act(async () => { await new Promise(r => setTimeout(r, 20)); });
    expect(forbidden.container.textContent).toBe("");
  });
});

describe("Critical banner carries recovery problems", () => {
  afterEach(cleanup);

  const serviceSilent = {
    alertId: "recovery:service-silent", sourceDeviceId: "recovery-service-silent", sourceName: "Recovery heartbeat service",
    location: "cabin", condition: "RECOVERY_SILENT", severity: "CRITICAL", evidenceAt: "2026-11-03T11:50:00Z",
    title: "Recovery service not reporting", detail: "No update from the host service for 10 minutes.",
  };

  it("counts a stale recovery service as a Critical condition", () => {
    render(
      <AppContext.Provider value={{
        activeLocation: "cabin", activeAlertLocations: ["cabin"], activeAlerts: [serviceSilent],
        automationAlerts: [], automationAlertsLoading: false,
      }}>
        <AlertControls panelId="MONITORING" />
      </AppContext.Provider>
    );
    expect(screen.getByText(/Critical — 1 current condition/)).toBeTruthy();
  });

  it("cannot be silenced open-endedly (the safety floor applies to it like any CRITICAL)", () => {
    const items = mergeStatusCheckItems([serviceSilent], "cabin", [], [{ alertKey: "recovery:service-silent", mode: "IGNORED" }]);
    expect(alertLevelFor(items)).toBe("critical");
    expect(items).toHaveLength(1);
  });

  it("carries a component that missed its heartbeats by name", () => {
    const down = { ...serviceSilent, alertId: "recovery:component:kafka", condition: "COMPONENT_DOWN", title: "kafka is down" };
    const items = mergeStatusCheckItems([down], "cabin", [], []);
    expect(items.map(i => i.title)).toEqual(["kafka is down"]);
  });
});
