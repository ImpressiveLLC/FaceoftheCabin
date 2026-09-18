import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

// Every `[data-theme="neon80s"] :is(a, b, c) { ... var(--layer-N-fill) ... }`
// rule, keyed by layer number -> set of class selectors it covers.
function layerMembership() {
  const layers = { 1: [], 2: [], 3: [] };
  const rule = /\[data-theme="neon80s"\]\s*:is\(([^)]*)\)\s*\{([^}]*)\}/g;
  for (const [, selectors, body] of css.matchAll(rule)) {
    const layer = body.match(/background:\s*var\(--layer-(\d)-fill\)/)?.[1];
    if (!layer) continue;
    layers[layer].push(...selectors.split(",").map(s => s.trim()).filter(Boolean));
  }
  return layers;
}

describe("80s Neon three-layer surface system", () => {
  it("defines the three layers with the chosen palette: pink primary, then #ffff66, then #7fffd4", () => {
    expect(css).toMatch(/\[data-theme="neon80s"\]\s*\{[^}]*--layer-1:\s*var\(--accent\)/);
    expect(css).toMatch(/--layer-2:\s*#ffff66/i);
    expect(css).toMatch(/--layer-3:\s*#7fffd4/i);
  });

  it("gives every layer a fill, an edge and a glow", () => {
    for (const n of [1, 2, 3]) {
      expect(css).toContain(`--layer-${n}-fill:`);
      expect(css).toContain(`--layer-${n}-edge:`);
      expect(css).toContain(`--layer-${n}-glow:`);
    }
  });

  it("puts panels, mid-size cards and small tiles in the right layer", () => {
    const layers = layerMembership();
    // largest -> pink
    for (const c of [".active-conditions", ".sidebar-card", ".config-card", ".dm-device-group", ".dm-detail"]) {
      expect(layers[1]).toContain(c);
    }
    // mid-size -> yellow
    for (const c of [".opportunity-card", ".device-card", ".dm-device-row"]) {
      expect(layers[2]).toContain(c);
    }
    // smallest tiles -> aquamarine
    for (const c of [".kpi-tile", ".camera-health-tile", ".capability-chip"]) {
      expect(layers[3]).toContain(c);
    }
  });

  it("never puts one component in two layers", () => {
    const layers = layerMembership();
    const all = [...layers[1], ...layers[2], ...layers[3]];
    expect(new Set(all).size).toBe(all.length);
  });

  it("does not recolor text: fills stay dark so the theme's light text reads on all three", () => {
    const rule = /\[data-theme="neon80s"\]\s*:is\(([^)]*)\)\s*\{([^}]*)\}/g;
    for (const [, , body] of css.matchAll(rule)) {
      if (!/--layer-\d-fill/.test(body)) continue;
      expect(body).not.toMatch(/(^|[;\s])color:/);
    }
  });

  it("is scoped to 80s Neon, so no other theme picks up the layers", () => {
    const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)];
    for (const [, selector, body] of rules) {
      if (!/var\(--layer-\d/.test(body)) continue;
      expect(selector).toContain('[data-theme="neon80s"]');
    }
  });
});
