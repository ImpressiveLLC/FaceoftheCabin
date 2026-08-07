import { describe, it, expect } from "vitest";
import { resolveInitialThemeId, THEMES } from "./ThemeProvider.jsx";

// Covers the actual reported bug this session (theme resets on cross-app
// link-out) at its root cause -- see docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md
// §2c/2d and this function's own comment in ThemeProvider.jsx.
describe("resolveInitialThemeId", () => {
  it("prefers a valid ?theme= URL param over the stored value", () => {
    const params = new URLSearchParams("?theme=lcars");
    expect(resolveInitialThemeId(params, "modern")).toBe("lcars");
  });

  it("falls back to the stored value when there is no theme param", () => {
    const params = new URLSearchParams("");
    expect(resolveInitialThemeId(params, "deepspace")).toBe("deepspace");
  });

  it("falls back to the stored value when the URL param names an unknown theme", () => {
    const params = new URLSearchParams("?theme=totally-not-a-real-theme");
    expect(resolveInitialThemeId(params, "bluefin")).toBe("bluefin");
  });

  it("falls back to modern when there is neither a valid param nor a stored value", () => {
    const params = new URLSearchParams("");
    expect(resolveInitialThemeId(params, null)).toBe("modern");
  });

  it("falls back to modern when the stored value itself is stale/unknown", () => {
    const params = new URLSearchParams("");
    expect(resolveInitialThemeId(params, "a-theme-that-was-since-removed")).toBe("modern");
  });

  it("recognizes every id actually defined in THEMES", () => {
    for (const id of Object.keys(THEMES)) {
      const params = new URLSearchParams(`?theme=${id}`);
      expect(resolveInitialThemeId(params, null)).toBe(id);
    }
  });
});
