import { describe, it, expect } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { THEMES } from "./ThemeProvider.jsx";
import { deriveSurfaceLayers, contrastRatio, huesAreDistinct } from "./surfaceLayers.js";

// Cabin UI and Family Hub are separate builds that must read as one design
// (their theme catalogs already had to be kept in sync -- see
// ThemeCatalogDrift.test.jsx). This is the same guard for the surface-layer
// system: the derivation function is embedded verbatim in family-hub.html, the
// hub carries the same three hue picks per theme, and the hub's own palettes
// satisfy the same can/can't spec. Resolution mirrors ThemeCatalogDrift: the
// cabin-ui CI image copies family-hub.html to /family-hub/.
const here = path.dirname(fileURLToPath(import.meta.url));
const hubPath = ["/family-hub/family-hub.html", path.join(here, "../../../family-hub/family-hub.html")].find(existsSync) || null;
const cabinSource = readFileSync(path.join(here, "surfaceLayers.js"), "utf8");

const block = (text) => {
  const a = text.indexOf("// <surface-layers>"), b = text.indexOf("// </surface-layers>");
  if (a < 0 || b < 0) throw new Error("surface-layers markers missing");
  return text.slice(a, b).replace(/^export /gm, "").replace(/\r/g, "").trim();
};

const parseHubThemes = (html) => {
  const start = html.indexOf("const THEMES = {");
  const end = /\r?\n\};\r?\n/.exec(html.slice(start));
  const body = html.slice(start, start + end.index);
  const out = {};
  for (const chunk of body.split(/\r?\n  (?=[a-z0-9]+: \{\r?\n\s+label:)/).slice(1)) {
    const id = chunk.match(/id:\s*'([^']+)'/)[1];
    const vars = Object.fromEntries([...chunk.matchAll(/'(--[a-z0-9-]+)'\s*:\s*(?:'([^']*)'|"([^"]*)")/g)].map(m => [m[1], m[2] ?? m[3]]));
    const layers = chunk.match(/layers:\s*\{([^}]*)\}/)?.[1] ?? "";
    out[id] = { vars, layers };
  }
  return out;
};
const hexArray = (s, key) => (s.match(new RegExp(`${key}:\\s*\\[([^\\]]*)\\]`))?.[1].match(/#[0-9a-fA-F]{6}/g) || []);

// Flatten a translucent hub color over the page, as the hub's own hubLayerVars does.
const toRgb = (h) => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const toHex = (n) => "#" + n.map(x => Math.round(x).toString(16).padStart(2, "0")).join("");
function over(color, base) {
  const m = color.match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?/);
  if (!m) return color;
  const a = m[4] === undefined ? 1 : +m[4], b = toRgb(base);
  return toHex([+m[1], +m[2], +m[3]].map((x, i) => x * a + b[i] * (1 - a)));
}

describe.skipIf(!hubPath)("surface layers: cabin-ui and family-hub stay one system", () => {
  const html = hubPath ? readFileSync(hubPath, "utf8") : "";
  const hub = hubPath ? parseHubThemes(html) : {};

  it("embeds the derivation byte-for-byte (minus `export`)", () => {
    expect(block(html)).toBe(block(cabinSource));
  });

  it("defines layer picks for exactly the themes cabin-ui has", () => {
    expect(Object.keys(hub).sort()).toEqual(Object.keys(THEMES).sort());
  });

  describe.each(Object.keys(THEMES))("%s", (id) => {
    const theme = THEMES[id];
    const pick = (r) => (r.startsWith("--") ? theme.vars[r] : r);
    const cabinHues = theme.layers.hues.map(pick).map(h => h.toLowerCase());

    it("uses the same three hues in both apps", () => {
      expect(hexArray(hub[id].layers, "hues").map(h => h.toLowerCase())).toEqual(cabinHues);
    });

    it("uses the same shadow style and tab color in both apps", () => {
      expect(hub[id].layers.match(/shadow:\s*'(\w+)'/)?.[1]).toBe(theme.layers.shadow);
      const cabinTab = theme.layers.tab ? pick(theme.layers.tab).toLowerCase() : undefined;
      expect(hub[id].layers.match(/tab:\s*'(#[0-9a-fA-F]{6})'/)?.[1].toLowerCase()).toBe(cabinTab);
    });

    it("satisfies the palette spec on the hub's own (flattened glass) palette", () => {
      const v = hub[id].vars, page = v["--night"];
      const palette = { page, panel: over(v["--glass"], page), border: over(v["--glass-border"], page), text: v["--cream"], muted: over(v["--cream-dim"], page), accent: v["--gold"] };
      const opts = { hues: cabinHues, shadow: theme.layers.shadow, widths: theme.layers.widths, edgeHue: theme.layers.edgeHue, tints: theme.layers.tints };
      const d = deriveSurfaceLayers(palette, opts);
      const [f1, f2, f3] = d.fills, [e1, e2, e3] = d.edges;
      expect(contrastRatio(e1, palette.page), "L1 edge vs page").toBeGreaterThanOrEqual(3);
      expect(contrastRatio(e2, f1), "L2 edge vs L1 fill").toBeGreaterThanOrEqual(3);
      expect(contrastRatio(e2, palette.page), "L2 edge vs page").toBeGreaterThanOrEqual(3);
      expect(contrastRatio(e3, f2), "L3 edge vs L2 fill").toBeGreaterThanOrEqual(3);
      expect(contrastRatio(e3, palette.page), "L3 edge vs page").toBeGreaterThanOrEqual(3);
      for (const f of [f1, f2, f3]) expect(contrastRatio(f, palette.text), "text on fill").toBeGreaterThanOrEqual(4.5);
      expect(huesAreDistinct(cabinHues[0], cabinHues[1])).toBe(true);
      expect(huesAreDistinct(cabinHues[1], cabinHues[2])).toBe(true);
    });
  });

  it("maps the hub's own classes into the three layers, with one tab color", () => {
    const lists = [...html.matchAll(/:is\(([^)]*)\)\s*\{\s*background:\s*var\(--layer-(\d)-fill\)/g)]
      .reduce((acc, m) => { (acc[m[2]] ||= []).push(...m[1].split(",").map(s => s.trim()).filter(Boolean)); return acc; }, {});
    for (const c of [".glass-card", ".dash-card", "#notepad-panel"]) expect(lists[1]).toContain(c);
    for (const c of [".bday-item", ".location-link", ".profile-card", ".reward-tile"]) expect(lists[2]).toContain(c);
    for (const c of [".sched-day-chip", ".theme-pill", ".kid-sel-btn"]) expect(lists[3]).toContain(c);
    const all = [...lists[1], ...lists[2], ...lists[3]];
    expect(new Set(all).size, "a class is in two layers").toBe(all.length);
    expect(html).toMatch(/\.d-tab,\s*\.d-tab:hover,\s*\.d-tab\.active\s*\{\s*color:\s*var\(--tab-color\)/);
  });
});

if (!hubPath) {
  // eslint-disable-next-line no-console
  console.warn("SKIPPED surface-layer drift check: family-hub.html not reachable from this environment.");
}
