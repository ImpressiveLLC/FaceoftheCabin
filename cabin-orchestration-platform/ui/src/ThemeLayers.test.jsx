import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { THEMES } from "./ThemeProvider.jsx";

const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].map(([, selector, body]) => ({
  selector: selector.trim().replace(/\s+/g, " "),
  body,
}));

// Every unscoped `:is(a, b, c) { background: var(--layer-N-fill) ... }` rule,
// keyed by layer number -> the class selectors it covers.
function layerMembership() {
  const layers = { 1: [], 2: [], 3: [] };
  for (const { selector, body } of rules) {
    const m = selector.match(/^:is\(([^)]*)\)$/);
    const layer = body.match(/background:\s*var\(--layer-(\d)-fill\)/)?.[1];
    if (!m || !layer) continue;
    layers[layer].push(...m[1].split(",").map(s => s.trim()).filter(Boolean));
  }
  return layers;
}

describe("surface layers apply to every theme", () => {
  it("derives the defaults from the core palette every theme already defines", () => {
    const root = rules.find(r => r.selector === ":root" && r.body.includes("--layer-1-fill"));
    expect(root).toBeTruthy();
    for (const n of [1, 2, 3]) {
      for (const part of ["fill", "edge", "shadow", "border-width"]) {
        expect(root.body).toContain(`--layer-${n}-${part}:`);
      }
    }
    // The defaults are only meaningful if each theme supplies what they read.
    for (const [id, theme] of Object.entries(THEMES)) {
      for (const key of ["--bg", "--bg-secondary", "--bg-tertiary", "--border", "--accent", "--text", "--text-muted"]) {
        expect(theme.vars[key], `${id} is missing ${key}`).toBeTruthy();
      }
    }
  });

  it("puts panels, mid-size cards and small tiles in the right layer", () => {
    const layers = layerMembership();
    for (const c of [".active-conditions", ".sidebar-card", ".config-card", ".dm-device-group", ".dm-detail"]) {
      expect(layers[1]).toContain(c);
    }
    for (const c of [".opportunity-card", ".device-card", ".dm-device-row"]) {
      expect(layers[2]).toContain(c);
    }
    for (const c of [".kpi-tile", ".camera-health-tile", ".capability-chip"]) {
      expect(layers[3]).toContain(c);
    }
  });

  it("never puts one component in two layers", () => {
    const layers = layerMembership();
    const all = [...layers[1], ...layers[2], ...layers[3]];
    expect(new Set(all).size).toBe(all.length);
  });

  it("does not recolor text: layer fills stay dark/derived so each theme's own text reads", () => {
    for (const { selector, body } of rules) {
      if (!/^:is\(/.test(selector) || !/--layer-\d-fill/.test(body)) continue;
      expect(body).not.toMatch(/(^|[;\s])color:/);
    }
  });

  it("keeps hard-coded surface colors out of the layer classes' own rules", () => {
    // The regression this system exists to prevent: a literal like #161b22 on a
    // panel ignores every theme, so sibling panels stop matching outside Modern.
    const layers = layerMembership();
    const layerClasses = new Set([...layers[1], ...layers[2], ...layers[3]]);
    for (const { selector, body } of rules) {
      if (selector.includes("[data-theme") || selector.startsWith("@")) continue;
      const parts = selector.split(",").map(s => s.trim());
      if (!parts.every(p => layerClasses.has(p))) continue;
      expect(body, `${selector} hard-codes a surface color`).not.toMatch(/(background(-color)?|border(-color)?)\s*:[^;]*#[0-9a-fA-F]{3,8}\b/);
    }
  });

  it("only :root and a theme block may define a layer token, never a component rule", () => {
    for (const { selector, body } of rules) {
      if (!/--layer-\d-(fill|edge|shadow|border-width)\s*:/.test(body)) continue;
      expect(selector === ":root" || /^\[data-theme="[a-z0-9]+"\]$/.test(selector), selector).toBe(true);
    }
  });
});

describe("80s Neon layers", () => {
  const neon = rules.find(r => r.selector === '[data-theme="neon80s"]' && r.body.includes("--layer-2:"));

  it("uses pink for the largest objects, then #ffff66, then #7fffd4", () => {
    expect(neon.body).toMatch(/--layer-1:\s*var\(--accent\)/);
    expect(neon.body).toMatch(/--layer-2:\s*#ffff66/i);
    expect(neon.body).toMatch(/--layer-3:\s*#7fffd4/i);
  });

  it("gives each layer a limited glow", () => {
    for (const n of [1, 2, 3]) expect(neon.body).toMatch(new RegExp(`--layer-${n}-shadow:\\s*0 0 `));
  });
});

describe("tab buttons share one color", () => {
  it("routes the nav rail through --tab-color, active state included", () => {
    expect(css).toMatch(/\.nav-item\s*\{\s*color:\s*var\(--tab-color\)/);
    expect(css).toMatch(/\.nav-rail \.nav-item\.nav-active\s*\{\s*color:\s*var\(--tab-color\)\s*!important/);
  });

  it("does not tint a tab's icon for a notification -- the dot badge is the indicator", () => {
    expect(css).not.toMatch(/\.nav-warn \.nav-alert-icon\s*\{[^}]*color:/);
  });

  it("makes every 80s Neon tab #ffff66", () => {
    const neon = rules.find(r => r.selector === '[data-theme="neon80s"]' && r.body.includes("--layer-2:"));
    expect(neon.body).toMatch(/--tab-color:\s*#ffff66/i);
  });
});
