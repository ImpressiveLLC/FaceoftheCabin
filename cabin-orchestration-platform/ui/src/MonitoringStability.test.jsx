import React, { Profiler, useState } from "react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act, cleanup } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { LiveEventLog, SensorHistoryPanel } from "./App.jsx";

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
  it("Sensor History tables use a fixed layout, a set first column and tabular figures", () => {
    expect(css.match(/\.sensor-history-table \{[^}]*\}/)[0]).toMatch(/font-variant-numeric:\s*tabular-nums/);
    expect(css).toMatch(/\.sensor-history-table--fixed \{[^}]*table-layout:\s*fixed[^}]*min-width:/);
    expect(css).toMatch(/\.sensor-history-table--fixed th:first-child[^}]*width:\s*13em/);
    expect(css).toMatch(/\.sensor-history-table--fixed th,[^}]*white-space:\s*nowrap/);
    // the presence "Day by day" table shares the base class and must keep its auto layout
    expect(css.match(/\.sensor-history-table \{[^}]*\}/)[0]).not.toMatch(/table-layout/);
  });

  it("the log reserves its height before the first message", () => {
    expect(css).toMatch(/\.event-log-body\s*\{\s*min-height:\s*169px/);
  });

  it("source guard: the section passes Sensor History one memoized array, and only the data table is fixed-layout", () => {
    // Not a behavioural test: LocationMonitoringSection is not exported and needs the whole app shell, so this only
    // guards the wiring that keeps the memoized panel's props stable.
    const app = readFileSync(path.join(here, "App.jsx"), "utf8");
    expect(app).toMatch(/const locDevices = useMemo\(\s*\(\) => devices\.filter\([^)]*\), \[devices, locCfg\.id\]\)/);
    expect(app).toMatch(/<SensorHistoryPanel devices=\{locDevices\}/);
    expect(app).not.toMatch(/<SensorHistoryPanel devices=\{devices\.filter/);
    expect((app.match(/sensor-history-table--fixed/g) || []).length).toBe(1);
  });

  it("the live stream has no throttle, debounce or batching around setMessages", () => {
    const app = readFileSync(path.join(here, "App.jsx"), "utf8");
    const hook = app.slice(app.indexOf("function useLiveEventStream"), app.indexOf("// ─── Panel: Family Hub"));
    expect(hook).not.toMatch(/setTimeout|debounce|throttle|requestAnimationFrame/);
  });
});
