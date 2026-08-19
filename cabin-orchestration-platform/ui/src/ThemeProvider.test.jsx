import { describe, it, expect } from "vitest";
import { resolveInitialThemeId, THEMES } from "./ThemeProvider.jsx";

// Test-only, not a theming utility this app itself needs elsewhere --
// see "Asteroid City's card surface is clearly distinguishable..." below.
function hexToRgb(hex) {
  const clean = hex.replace("#", "");
  return {
    r: parseInt(clean.slice(0, 2), 16),
    g: parseInt(clean.slice(2, 4), 16),
    b: parseInt(clean.slice(4, 6), 16),
  };
}

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
  it("maps the supplied Asteroid City palette into the cabin UI variables", () => {
    const theme = THEMES.asteroidcity;
    expect(theme.label).toBe("Asteroid City");
    expect(theme.vars).toMatchObject({
      "--bg": "#009ca6",
      "--bg-secondary": "#dcb881",
      "--accent": "#e34e24",
      "--border": "#1f3438",
      "--bg-tertiary": "#608c4a",
    });
  });
  // 2026-08-19 (user report, with screenshot): the theme's own card surface
  // must be clearly distinguishable from its own page background -- this
  // was the actual bug, not a specific hex pairing. Locks in the fix as a
  // property (real hue distance, not "any two different-looking colors")
  // rather than pinning it to today's literal choice of sand vs. turquoise,
  // so a future palette nudge can't silently regress the same complaint.
  it("Asteroid City's card surface is clearly distinguishable from its own page background", () => {
    const { vars } = THEMES.asteroidcity;
    const bg = hexToRgb(vars["--bg"]);
    const surface = hexToRgb(vars["--bg-secondary"]);
    const distance = Math.sqrt(
      (bg.r - surface.r) ** 2 + (bg.g - surface.g) ** 2 + (bg.b - surface.b) ** 2);
    expect(distance).toBeGreaterThan(120);
  });

});
