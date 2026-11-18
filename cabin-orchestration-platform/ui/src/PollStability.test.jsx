import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { keepIfSame } from "./App.jsx";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = readFileSync(path.join(here, "App.jsx"), "utf8");

// Nate, 2026-11-16: cards refreshed, shifted and resized every few seconds on tablet and phone. The device list
// is polled every 15 s and was replaced with a new array each time even when identical, re-rendering every card.
describe("keepIfSame keeps the previous reference when a poll brings nothing new", () => {
  const a = [{ id: "d1", state: "on", attrs: { t: 1 } }, { id: "d2", state: "off" }];

  it("returns the previous array for an identical payload", () => {
    const next = JSON.parse(JSON.stringify(a));
    expect(next).not.toBe(a);
    expect(keepIfSame(a, next)).toBe(a);
  });

  it("returns the new array when anything changed, including one nested value", () => {
    const next = JSON.parse(JSON.stringify(a));
    next[0].attrs.t = 2;
    expect(keepIfSame(a, next)).toBe(next);
  });

  it("returns the new array when a device is added or the order changes", () => {
    expect(keepIfSame(a, [...a, { id: "d3" }])).toEqual([...a, { id: "d3" }]);
    const reversed = [...a].reverse();
    expect(keepIfSame(a, reversed)).toBe(reversed);
  });

  it("never throws on unserialisable input and falls back to the new value", () => {
    const circular = {}; circular.self = circular;
    expect(keepIfSame([], circular)).toBe(circular);
  });
});

describe("the polled lists use it", () => {
  it("refreshDevices replaces devices through keepIfSame", () => {
    expect(app).toMatch(/setDevices\(prev => keepIfSame\(prev, next\)\)/);
    expect(app).not.toMatch(/setDevices\(succeeded/);
  });
  it("refreshWorkflows replaces workflows through keepIfSame", () => {
    expect(app).toMatch(/setWorkflows\(prev => keepIfSame\(prev, next\)\)/);
    expect(app).not.toMatch(/setWorkflows\(results\.flat\(\)\)/);
  });
});
