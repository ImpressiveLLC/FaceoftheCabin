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

  it("puts list rows in layer 2 and their tags in layer 3, so every box shares one anatomy", () => {
    const layers = layerMembership();
    for (const c of [".rule-row", ".active-condition"]) expect(layers[2]).toContain(c);
    expect(layers[3]).toContain(".meta-chip");
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

  it("keeps hard-coded colors out of every component rule, not just surfaces", () => {
    // The app used GitHub-dark literals (#8b949e text, #21262d borders, #3fb950
    // status ...): fine in Modern, whose palette is those values, wrong in every
    // other theme. Colors come from theme tokens. Allowed: black behind video,
    // and the category badge's purple, which is a category color not a theme one.
    const ALLOWED = new Set([".camera-clip-player", ".camera-live-view img", ".category-badge"]);
    for (const { selector, body } of rules) {
      if (selector.startsWith(":root") || selector.startsWith("@") || selector.includes("[data-theme")) continue;
      if (selector.split(",").every(p => ALLOWED.has(p.trim()))) continue;
      const bad = body.match(/(?:^|[;\s])(?:background(?:-color)?|border[\w-]*|color|outline[\w-]*)\s*:[^;]*#[0-9a-fA-F]{3,8}\b/);
      expect(bad, `${selector} hard-codes a color`).toBeNull();
    }
  });

  it("only :root and a theme block may define a layer token, never a component rule", () => {
    for (const { selector, body } of rules) {
      if (!/--layer-\d-(fill|edge|shadow|border-width)\s*:/.test(body)) continue;
      expect(selector === ":root" || /^\[data-theme="[a-z0-9]+"\]$/.test(selector), selector).toBe(true);
    }
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

  it("makes every 80s Neon tab #ffff66 (theme data, not a stylesheet override)", () => {
    expect(THEMES.neon80s.layers.tab).toBe("--warning");
    expect(THEMES.neon80s.vars["--warning"].toLowerCase()).toBe("#ffff66");
  });
});
