import { describe, it, expect } from "vitest";
import { keepIfSameDevices } from "./App.jsx";

const dev = (over = {}) => ({ deviceId: "a", state: "ONLINE", lastSeen: "2026-11-23T04:00:00Z", attributes: { value: "72.5" }, ...over });

describe("keepIfSameDevices", () => {
  it("keeps the previous list when only lastSeen moved a little", () => {
    const prev = [dev()];
    expect(keepIfSameDevices(prev, [dev({ lastSeen: "2026-11-23T04:00:15Z" })])).toBe(prev);
  });
  it("replaces the list when a reading changes", () => {
    const prev = [dev()], next = [dev({ attributes: { value: "73.0" } })];
    expect(keepIfSameDevices(prev, next)).toBe(next);
  });
  it("replaces the list when a state changes", () => {
    const prev = [dev()], next = [dev({ state: "OFFLINE" })];
    expect(keepIfSameDevices(prev, next)).toBe(next);
  });
  it("replaces the list when lastSeen jumps 5 minutes or more", () => {
    const prev = [dev()], next = [dev({ lastSeen: "2026-11-23T04:06:00Z" })];
    expect(keepIfSameDevices(prev, next)).toBe(next);
  });
  it("replaces the list when a device is added or removed", () => {
    const prev = [dev()], next = [dev(), dev({ deviceId: "b" })];
    expect(keepIfSameDevices(prev, next)).toBe(next);
  });
});
