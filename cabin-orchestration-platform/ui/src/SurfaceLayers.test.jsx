import { describe, it, expect } from "vitest";
import { THEMES, layerVarsFor } from "./ThemeProvider.jsx";
import { deriveSurfaceLayers, contrastRatio, huesAreDistinct } from "./surfaceLayers.js";

// The palette spec, enforced over EVERY theme. It is the CAN / CAN'T list in
// surfaceLayers.js and docs/UX_JOURNEY_STANDARD.md "Visual system" -- a theme
// whose palette can't satisfy it fails the build instead of shipping a panel you
// can't see.
const EDGE = 3.0;   // WCAG 1.4.11 non-text contrast for a UI boundary
const TEXT = 4.5;   // WCAG 1.4.3 body text

const pick = (theme, role) => (role.startsWith("--") ? theme.vars[role] : role);
function resolve(theme) {
  const v = theme.vars, L = theme.layers;
  const palette = { page: v["--bg"], panel: v["--bg-secondary"], border: v["--border"], text: v["--text"], muted: v["--text-muted"], accent: v["--accent"] };
  const hues = L.hues.map(r => pick(theme, r));
  return { palette, hues, derived: deriveSurfaceLayers(palette, { ...L, hues, tab: L.tab && pick(theme, L.tab) }) };
}

describe.each(Object.entries(THEMES))("%s palette spec", (id, theme) => {
  const { palette, hues, derived } = resolve(theme);
  const [f1, f2, f3] = derived.fills;
  const [e1, e2, e3] = derived.edges;

  it("has three layer hues that come from its own palette", () => {
    expect(hues).toHaveLength(3);
    hues.forEach(h => expect(h, `${id} hue`).toMatch(/^#[0-9a-fA-F]{6}$/));
  });

  it("CAN border: every layer's edge separates from what it sits on", () => {
    expect(contrastRatio(e1, palette.page), "L1 edge vs page").toBeGreaterThanOrEqual(EDGE);
    expect(contrastRatio(e2, f1), "L2 edge vs L1 fill").toBeGreaterThanOrEqual(EDGE);
    expect(contrastRatio(e3, f2), "L3 edge vs L2 fill").toBeGreaterThanOrEqual(EDGE);
    expect(contrastRatio(e3, f1), "L3 edge vs L1 fill").toBeGreaterThanOrEqual(EDGE);
  });

  it("CAN'T overlap: adjacent layers never share a hue (no pink-on-pink)", () => {
    expect(huesAreDistinct(hues[0], hues[1]), "L1 vs L2").toBe(true);
    expect(huesAreDistinct(hues[1], hues[2]), "L2 vs L3").toBe(true);
  });

  it("CAN'T recolor text: the theme's own text reads on every fill", () => {
    for (const fill of [f1, f2, f3]) {
      expect(contrastRatio(fill, palette.text)).toBeGreaterThanOrEqual(TEXT);
    }
  });

  it("CAN'T use the danger color as structure", () => {
    expect(hues.map(h => h.toLowerCase())).not.toContain(theme.vars["--danger"].toLowerCase());
  });

  it("sets every layer variable, so switching themes can't leave a stale one", () => {
    const vars = layerVarsFor(theme);
    for (const n of [1, 2, 3]) {
      for (const part of ["", "-fill", "-edge", "-shadow", "-border-width"]) {
        expect(vars[`--layer-${n}${part}`], `--layer-${n}${part}`).toBeTruthy();
      }
    }
    for (const key of ["--layer-hover-edge", "--layer-hover-shadow", "--layer-selected-edge", "--layer-selected-shadow", "--tab-color", "--tab-color-hover", "--on-accent"]) {
      expect(vars[key], key).toBeTruthy();
    }
  });

  it("picks black or white for text on the accent, whichever reads better", () => {
    const onAccent = layerVarsFor(theme)["--on-accent"];
    const other = onAccent === "#ffffff" ? "#000000" : "#ffffff";
    expect(contrastRatio(theme.vars["--accent"], onAccent)).toBeGreaterThanOrEqual(contrastRatio(theme.vars["--accent"], other));
  });
});

describe("the derivation balances a low-visibility palette by itself", () => {
  // Modern's real palette: panel #161b22 on page #0d1117 with a #21262d edge was
  // 1.24:1 -- the "can't see the panels" report.
  const modern = { page: "#0d1117", panel: "#161b22", border: "#21262d", text: "#e6edf3", muted: "#8b949e", accent: "#1f6feb" };

  it("strengthens a dim hue until its edge separates", () => {
    const d = deriveSurfaceLayers(modern, { hues: ["#21262d", "#30363d", "#3a4048"] });
    expect(contrastRatio(d.edges[0], modern.page)).toBeGreaterThanOrEqual(EDGE);
    expect(contrastRatio(d.edges[1], d.fills[0])).toBeGreaterThanOrEqual(EDGE);
  });

  it("flags two near-identical hues as not distinct, and clearly different ones as distinct", () => {
    expect(huesAreDistinct("#ff2dd4", "#ff5fe0")).toBe(false);
    expect(huesAreDistinct("#ff2dd4", "#add8e6")).toBe(true);
    expect(huesAreDistinct("#8b949e", "#e6edf3")).toBe(true);
  });

  it("backs a tint off rather than let it sink the text contrast", () => {
    const d = deriveSurfaceLayers(modern, { hues: ["#ffffff", "#ffffff", "#ffffff"], tints: [0.9, 0.9, 0.9] });
    for (const fill of d.fills) expect(contrastRatio(fill, modern.text)).toBeGreaterThanOrEqual(TEXT);
  });
});

describe("80s Neon: pink main, light blue frames, yellow tiles", () => {
  const { hues, derived } = resolve(THEMES.neon80s);

  it("uses the pink primary, then the palette's light blue, then yellow", () => {
    expect(hues.map(h => h.toLowerCase())).toEqual(["#ff2dd4", "#add8e6", "#ffff66"]);
  });

  it("glows on every layer and makes every tab yellow", () => {
    derived.shadows.forEach(s => expect(s).toMatch(/^0 0 /));
    expect(derived.tab.toLowerCase()).toBe("#ffff66");
  });
});
