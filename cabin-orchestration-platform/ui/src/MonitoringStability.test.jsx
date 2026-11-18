import React, { Profiler, useState } from "react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act, cleanup } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { LiveEventLog, SensorHistoryPanel, MnSeeView, AppContext, renderProbes } from "./App.jsx";

const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "styles.css"), "utf8");

// Nate, 2026-11-17 (go 01:08, rule 01:11 CST): the Monitoring view shifted every 4 to 5 s. Fix: isolate the live log so a
// message re-renders only the log, memoize Sensor History, reserve layout space. Every message must still appear at once.
class FakeEventSource {
  static instances = [];
  constructor(url) { this.url = url; this.listeners = {}; FakeEventSource.instances.push(this); }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  close() { this.closed = true; }
  emit(obj) { this.listeners["cabin-event"]?.({ data: JSON.stringify(obj) }); }
}

const loc = { id: "cabin", label: "Cabin", apiBase: "http://cabin" };

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  try { localStorage.clear(); } catch {}
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("live log: every message appears immediately, nothing is throttled", () => {
  it("shows each message in sequence as soon as it arrives, newest first", () => {
    render(<LiveEventLog locCfg={loc} active cabinSession="tok" />);
    const es = FakeEventSource.instances[0];
    expect(es.url).toContain("/api/events/live?cabin_session=tok");
    for (const n of [1, 2, 3, 4, 5]) {
      act(() => es.emit({ type: "T", n }));
      expect(document.querySelectorAll(".event-row")).toHaveLength(n);
      expect(document.querySelector(".event-row .event-body").textContent).toContain(`"n":${n}`);
    }
  });

  it("keeps the 50 message cap and does not drop the newest", () => {
    render(<LiveEventLog locCfg={loc} active cabinSession="tok" />);
    const es = FakeEventSource.instances[0];
    act(() => { for (let n = 1; n <= 60; n++) es.emit({ n }); });
    const rows = document.querySelectorAll(".event-row");
    expect(rows).toHaveLength(50);
    expect(rows[0].textContent).toContain('"n":60');
  });
});

describe("delta-only re-render", () => {
  it("one message re-renders only the log, not a sibling Sensor History panel", () => {
    const panel = vi.fn(), log = vi.fn();
    const devices = [];
    render(
      <div>
        <Profiler id="panel" onRender={panel}>
          <SensorHistoryPanel devices={devices} apiBase="http://cabin" location="cabin" tempUnit="F" />
        </Profiler>
        <Profiler id="log" onRender={log}>
          <LiveEventLog locCfg={loc} active cabinSession="tok" />
        </Profiler>
      </div>
    );
    const panelBase = panel.mock.calls.length, logBase = log.mock.calls.length;
    act(() => FakeEventSource.instances[0].emit({ n: 1 }));
    act(() => FakeEventSource.instances[0].emit({ n: 2 }));
    expect(panel.mock.calls.length).toBe(panelBase);
    expect(log.mock.calls.length).toBeGreaterThan(logBase);
  });

  it("Sensor History skips a parent re-render that passes identical props", () => {
    // A Profiler around the panel would itself re-render with the parent, so count reads of the devices prop instead:
    // the panel reads it on every render of its own, and not otherwise.
    let reads = 0;
    const devices = new Proxy([], { get(target, key, receiver) { reads += 1; return Reflect.get(target, key, receiver); } });
    let bump;
    function Parent() {
      const [n, setN] = useState(0);
      bump = () => setN(v => v + 1);
      return <div data-n={n}>
        <SensorHistoryPanel devices={devices} apiBase="http://cabin" location="cabin" tempUnit="F" />
      </div>;
    }
    render(<Parent />);
    expect(reads).toBeGreaterThan(0);
    const base = reads;
    act(() => bump());
    expect(document.querySelector("[data-n='1']")).not.toBeNull();
    expect(reads).toBe(base);
  });

  it("a changed value is never frozen: new devices array renders the panel again", () => {
    const panel = vi.fn();
    const { rerender } = render(
      <Profiler id="panel" onRender={panel}>
        <SensorHistoryPanel devices={[]} apiBase="http://cabin" location="cabin" tempUnit="F" />
      </Profiler>);
    const base = panel.mock.calls.length;
    rerender(
      <Profiler id="panel" onRender={panel}>
        <SensorHistoryPanel devices={[]} apiBase="http://cabin" location="cabin" tempUnit="C" />
      </Profiler>);
    expect(panel.mock.calls.length).toBeGreaterThan(base);
  });

  it("existing rows keep their DOM nodes when a new message arrives", () => {
    render(<LiveEventLog locCfg={loc} active cabinSession="tok" />);
    const es = FakeEventSource.instances[0];
    act(() => es.emit({ n: 1 }));
    const first = document.querySelector(".event-row");
    act(() => es.emit({ n: 2 }));
    const rows = [...document.querySelectorAll(".event-row")];
    // The older message moves down one place but is the same DOM node, still showing n:1; with position keys the
    // node at index 0 would be reused for the newer message and this would fail.
    expect(rows[1]).toBe(first);
    expect(rows[1].textContent).toContain('"n":1');
    expect(rows[0].textContent).toContain('"n":2');
  });
});

describe("layout contract (jsdom has no layout engine, so this checks the CSS that carries it)", () => {
  it("Sensor History data table: fixed layout from <col> widths, nothing clipped, presence table untouched", () => {
    expect(css.match(/\.sensor-history-table \{[^}]*\}/)[0]).toMatch(/font-variant-numeric:\s*tabular-nums/);
    expect(css.match(/\.sensor-history-table \{[^}]*\}/)[0]).not.toMatch(/table-layout/);
    expect(css).toMatch(/\.sensor-history-table--fixed \{\s*table-layout:\s*fixed/);
    expect(css).toMatch(/\.sh-col-date \{\s*width:\s*13em/);
    expect(css).toMatch(/\.sh-col-device \{\s*width:\s*11em/);
    expect(css).toMatch(/\.sh-col-outdoor \{\s*width:\s*30em/);
    // the outdoor reading is long (mean, low, high, count, partial): it wraps inside its column, never clipped
    expect(css).toMatch(/td\.sh-cell-outdoor \{\s*white-space:\s*normal/);
    // header cells wrap inside their fixed column (a long device name must not paint over the next header)
    expect(css).toMatch(/\.sensor-history-table--fixed th \{[^}]*white-space:\s*normal[^}]*overflow-wrap:\s*anywhere/);
    expect(css).toMatch(/\.sensor-history-table--fixed td \{\s*white-space:\s*nowrap/);
    expect(css).not.toMatch(/\.sensor-history-table--fixed th,\s*\.sensor-history-table--fixed td\s*\{[^}]*nowrap/);
    const fixedRules = css.match(/\.sensor-history-table--fixed[^{]*\{[^}]*\}/g).join(" ");
    expect(fixedRules).not.toMatch(/text-overflow|overflow:\s*hidden/);
  });

  it("the log reserves its height before the first message", () => {
    expect(css).toMatch(/\.event-log-body\s*\{\s*min-height:\s*169px/);
  });

  it("the live stream has no throttle, debounce or batching around setMessages", () => {
    const app = readFileSync(path.join(here, "App.jsx"), "utf8");
    const hook = app.slice(app.indexOf("function useLiveEventStream"), app.indexOf("// ─── Panel: Family Hub"));
    expect(hook).not.toMatch(/setTimeout|debounce|throttle|requestAnimationFrame/);
  });
});

describe("render counts through the real parent and provider (MnSeeView inside an AppContext rebuilt on every render)", () => {
  const device = (over = {}) => ({ deviceId: "t1", name: "Temp A", type: "TEMPERATURE_SENSOR", state: "ONLINE",
    location: "cabin", attributes: { enabled: true, temperature: 70 }, ...over });
  let renders;
  const setActivePanel = vi.fn();
  beforeEach(() => {
    renders = 0;
    renderProbes.sensorHistory = () => { renders += 1; };
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, json: async () => ({}) })));
  });
  afterEach(() => { delete renderProbes.sensorHistory; });

  function Harness({ devices, bumpRef }) {
    const [n, setN] = useState(0);
    bumpRef.current = () => setN(v => v + 1);
    // new value object every render, exactly like App's Provider (setActivePanel is a useState setter in App: stable)
    return (
      <AppContext.Provider value={{ displayConfigs: {}, setActivePanel, n }}>
        <MnSeeView devices={devices} activeLocation="cabin" active reorderMode={false}
          auth={{ cabinSessionToken: "tok", signedIn: true, authedFetch: fetch }} />
      </AppContext.Provider>
    );
  }

  it("neither a parent or context re-render with unchanged devices nor a stream message re-renders Sensor History", async () => {
    const devices = [device()];
    const bumpRef = { current: null };
    render(<Harness devices={devices} bumpRef={bumpRef} />);
    await act(async () => {});
    expect(renders).toBeGreaterThan(0);
    const base = renders;
    act(() => bumpRef.current());
    act(() => bumpRef.current());
    expect(renders).toBe(base);
    const es = FakeEventSource.instances[0];
    act(() => es.emit({ n: 1 }));
    act(() => es.emit({ n: 2 }));
    expect(document.querySelectorAll(".event-row")).toHaveLength(2);
    expect(renders).toBe(base);
  });

  it("a genuinely changed device list does render it again (nothing is frozen)", async () => {
    const bumpRef = { current: null };
    const { rerender } = render(<Harness devices={[device()]} bumpRef={bumpRef} />);
    await act(async () => {});
    const base = renders;
    rerender(<Harness devices={[device({ attributes: { enabled: true, temperature: 71 } })]} bumpRef={bumpRef} />);
    await act(async () => {});
    expect(renders).toBeGreaterThan(base);
  });
});
