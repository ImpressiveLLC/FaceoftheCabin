import React from "react";
import { describe, it, expect, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, act } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { ThemeProvider, THEMES, useTheme, layerVarsFor } from "./ThemeProvider.jsx";
import { hueAngle, huesAreDistinct, contrastRatio } from "./surfaceLayers.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const hub = readFileSync(path.join(here, "..", "..", "..", "family-hub", "family-hub.html"), "utf8");

const resolve = (theme, role) => (role.startsWith("--") ? theme.vars[role] : role);

// Reported 2026-09-19: LCARS was orange on black end to end and tiring to read.
// The palette is the Okudagrams "complete set"; orange stays the primary.
describe("LCARS uses more than orange", () => {
  const lcars = THEMES.lcars;
  const hueOf = (hex) => hueAngle(hex).h;

  it("keeps orange as the primary: accent, focus, and the tab outline", () => {
    for (const hex of [lcars.vars["--accent"], lcars.vars["--border-focus"]]) {
      expect(hueOf(hex)).toBeGreaterThan(25);
      expect(hueOf(hex)).toBeLessThan(45);
    }
    // The active tab's outline is drawn from --accent whatever color its label is.
    const outline = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
      .filter(([, sel, body]) => sel.trim() === ".nav-active" && /border-color:\s*var\(--accent\)/.test(body));
    expect(outline.length).toBeGreaterThan(0);
  });

  // Reported 2026-09-20: page titles and tab labels share one non-orange color;
  // table headers take a fourth; panels are black like the real control panels.
  it("colors page titles and tab labels the same, in a hue that isn't the primary", () => {
    const title = lcars.vars["--title-color"];
    expect(resolve(lcars, lcars.layers.tab)).toBe(title);
    expect(layerVarsFor(lcars)["--tab-color"]).toBe(title);
    expect(huesAreDistinct(title, lcars.vars["--accent"])).toBe(true);
    expect(contrastRatio(title, lcars.vars["--bg"])).toBeGreaterThanOrEqual(4.5);
  });

  it("gives table headers a fourth color, distinct from the primary, the title and the muted text", () => {
    const head = lcars.vars["--table-head"];
    for (const other of [lcars.vars["--accent"], lcars.vars["--title-color"], lcars.vars["--text-muted"], lcars.vars["--text"]]) {
      expect(huesAreDistinct(head, other)).toBe(true);
    }
    expect(contrastRatio(head, lcars.vars["--bg"])).toBeGreaterThanOrEqual(4.5);
  });

  it("uses the token for titles and table headers, with a fallback so other themes are unchanged", () => {
    const rule = (sel) => [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].find(([, s]) => s.trim() === sel)?.[2] ?? "";
    expect(rule(".panel-header-bar h2")).toContain("color: var(--title-color, var(--text))");
    expect(rule(".sensor-history-table th")).toContain("color: var(--table-head, var(--text-dim))");
    expect(rule(".platform-info-table td:first-child")).toContain("color: var(--table-head, var(--text))");
    for (const [id, theme] of Object.entries(THEMES)) {
      if (id !== "lcars") { expect(theme.vars["--title-color"]).toBeUndefined(); expect(theme.vars["--table-head"]).toBeUndefined(); }
    }
  });

  it("puts panels, cards and tiles on the default black, told apart by their colored edges", () => {
    expect(lcars.vars["--bg-secondary"]).toBe(lcars.vars["--bg"]);
    expect(lcars.vars["--surface"]).toBe(lcars.vars["--bg"]);
    const v = layerVarsFor(lcars);
    for (const n of [1, 2, 3]) expect(v[`--layer-${n}-fill`]).toBe("#000000");
    // The only other background is the hover shade, and it is a neutral grey rather than another hue.
    const [r, g, b] = [1, 3, 5].map(i => parseInt(lcars.vars["--bg-tertiary"].slice(i, i + 2), 16));
    expect(r === g && g === b).toBe(true);
  });

  it("gives panels, cards and tiles three different hues, largest first orange", () => {
    const [h1, h2, h3] = lcars.layers.hues.map(r => resolve(lcars, r));
    expect(hueOf(h1)).toBeLessThan(45);
    expect(huesAreDistinct(h1, h2)).toBe(true);
    expect(huesAreDistinct(h2, h3)).toBe(true);
    expect(huesAreDistinct(h1, h3)).toBe(true);
  });

  it("reads in more than one hue: text, muted and dim text are not all orange", () => {
    const hsvSaturation = (hex) => { const v = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)); return (Math.max(...v) - Math.min(...v)) / Math.max(...v); };
    const orange = (hex) => hueOf(hex) > 20 && hueOf(hex) < 50 && hsvSaturation(hex) > 0.8;
    expect(orange(lcars.vars["--accent"])).toBe(true);         // the check does recognise orange
    expect(orange(lcars.vars["--text"])).toBe(false);          // peach: same warm hue, far less saturated
    expect(orange(lcars.vars["--text-muted"])).toBe(false);
    expect(orange(lcars.vars["--text-dim"])).toBe(false);
    expect(huesAreDistinct(lcars.vars["--text-muted"], lcars.vars["--accent"])).toBe(true);
    expect(huesAreDistinct(lcars.vars["--text-dim"], lcars.vars["--accent"])).toBe(true);
  });

  it("still meets the contrast floors on the page and panel", () => {
    for (const role of ["--text", "--text-muted", "--text-dim"]) {
      expect(contrastRatio(lcars.vars[role], lcars.vars["--bg"])).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(lcars.vars[role], lcars.vars["--bg-secondary"])).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("Family Hub's LCARS uses the same layer hues, tab label color and black panels", () => {
    const block = hub.match(/\r?\n  lcars: \{[\s\S]*?\r?\n  \},\r?\n/)?.[0] ?? "";
    const hues = block.match(/layers:\s*\{\s*hues:\s*\[([^\]]*)\]/)?.[1].match(/#[0-9a-fA-F]{6}/g);
    expect(hues).toEqual(lcars.layers.hues.map(r => resolve(lcars, r)));
    expect(block).toContain(`tab: '${lcars.vars["--title-color"]}'`);
    expect(block).toContain("tints: [0, 0, 0]");
    expect(block).toMatch(/'--glass':'rgba\(0,0,0,/);
  });
});

// Reported 2026-09-19: selected data in Pac-Man, Impressive and Deep Space needs
// the same heavy back-lit glow Deep Space already puts behind its active tab.
describe("selected data glow", () => {
  const glowOf = (hex) => `0 0 8px ${hex}, 0 0 24px ${hex}40`;

  it("is defined for exactly Deep Space, Pac-Man and Impressive", () => {
    const withGlow = Object.values(THEMES).filter(t => t.vars["--selected-glow"]).map(t => t.id).sort();
    expect(withGlow).toEqual(["deepspace", "impressive", "pacman"]);
  });

  it("is Deep Space's own tab glow there, and the same recipe in each theme's selected hue", () => {
    expect(THEMES.deepspace.vars["--selected-glow"]).toBe(THEMES.deepspace.vars["--glow-hal"]);
    expect(THEMES.pacman.vars["--selected-glow"]).toBe(glowOf(THEMES.pacman.vars["--accent"]));
    expect(THEMES.impressive.vars["--selected-glow"]).toBe(glowOf(THEMES.impressive.vars["--accent-hover"]));
  });

  it("carries one color per theme that matches its glow, and only those three themes define it", () => {
    for (const id of ["deepspace", "pacman", "impressive"]) {
      const { "--selected-color": color, "--selected-glow": glow } = THEMES[id].vars;
      expect(color).toMatch(/^#[0-9a-f]{6}$/i);
      expect(glow).toContain(color);
    }
    expect(Object.values(THEMES).filter(t => t.vars["--selected-color"]).map(t => t.id).sort())
      .toEqual(["deepspace", "impressive", "pacman"]);
    expect(THEMES.deepspace.vars["--selected-color"]).toBe(THEMES.deepspace.vars["--danger"]);   // the active tab's HAL red
  });

  it("is applied to selected cards, rows, chips and topic tabs", () => {
    const rule = (needle) => css.split("}").find(r => r.includes(needle)) ?? "";
    const rows = rule(".device-card.selected, .dm-row-selected");
    expect(rows).toContain("var(--selected-glow, var(--layer-selected-shadow))");
    const chips = rule(".sensor-history-topic-tab.active, .sensor-history-device-chip.selected");
    expect(chips).toContain("var(--selected-glow, none)");
  });

  // Reported 2026-09-20: in Monitoring > Change the rows blended together and the
  // selected one was easy to lose. Every theme gets three cues that don't need a glow.
  it("marks a selected row three ways in every theme: full-color edge, thick left bar, tint", () => {
    const rule = (needle) => css.split("}").find(r => r.includes(needle)) ?? "";
    const rows = rule(".device-card.selected, .dm-row-selected");
    const color = "var(--selected-color, var(--layer-selected-edge))";
    expect(rows).toContain(`border-color: ${color}`);
    expect(rows).toContain(`inset 5px 0 0 ${color}`);
    expect(rows).toMatch(/background:\s*color-mix\(in srgb, var\(--selected-color, var\(--layer-selected-edge\)\) 16%/);
    // The earlier !important edge rule must use the same color or it would win.
    expect(rule(".dm-row-selected { border-color")).toContain(`border-color: ${color} !important`);
    // Themes without their own selected color fall back to the layer's selected edge, which every theme defines.
    for (const theme of Object.values(THEMES)) expect(layerVarsFor(theme)["--layer-selected-edge"]).toMatch(/^#[0-9a-f]{6}$/i);
  });
});

// A theme lists only the variables it needs, so a value one theme set used to
// stay on <html> after switching to a theme that doesn't define it.
describe("switching themes leaves nothing behind", () => {
  afterEach(() => { cleanup(); document.documentElement.removeAttribute("style"); localStorage.clear(); });

  function Switcher() {
    const { setTheme } = useTheme();
    return <>{Object.keys(THEMES).map(id => <button key={id} onClick={() => setTheme(id)}>{id}</button>)}</>;
  }
  const root = () => document.documentElement.style;

  it("removes Deep Space's glow variables when moving to a theme without them", () => {
    localStorage.setItem("cabin-theme", "deepspace");
    render(<ThemeProvider><Switcher /></ThemeProvider>);
    expect(root().getPropertyValue("--selected-glow")).toBe(THEMES.deepspace.vars["--selected-glow"]);
    expect(root().getPropertyValue("--glow-hal")).not.toBe("");

    fireEvent.click(screen.getByRole("button", { name: "modern" }));

    expect(root().getPropertyValue("--selected-glow")).toBe("");
    expect(root().getPropertyValue("--glow-hal")).toBe("");
    expect(root().getPropertyValue("--bg")).toBe(THEMES.modern.vars["--bg"]);
  });

  it("carries a theme's own variables when moving between two themes that both define them", () => {
    localStorage.setItem("cabin-theme", "pacman");
    render(<ThemeProvider><Switcher /></ThemeProvider>);

    fireEvent.click(screen.getByRole("button", { name: "impressive" }));

    expect(root().getPropertyValue("--selected-glow")).toBe(THEMES.impressive.vars["--selected-glow"]);
  });
});

// Reported 2026-09-20: Deep Space showed brown / orange box outlines (its cards
// used the amber warning color as their layer hue). Cards are a bold dark gray.
describe("Deep Space has no orange or brown box edges", () => {
  const ds = THEMES.deepspace;
  const vars = layerVarsFor(ds);
  const warmOrBrown = (hex) => { const { h, s } = hueAngle(hex); return s > 0.25 && h >= 10 && h <= 55; };

  it("uses no warm hue for any layer, edge or fill", () => {
    for (const role of ds.layers.hues) expect(warmOrBrown(resolve(ds, role))).toBe(false);
    for (const n of [1, 2, 3]) {
      expect(warmOrBrown(vars[`--layer-${n}-edge`])).toBe(false);
      expect(warmOrBrown(vars[`--layer-${n}-fill`])).toBe(false);
    }
  });

  it("draws cards in a neutral gray, bolder (2px) than panels and tiles, that still separates from the panel", () => {
    expect(hueAngle(vars["--layer-2-edge"]).s).toBeLessThan(0.2);
    expect(vars["--layer-2-border-width"]).toBe("2px");
    expect(vars["--layer-1-border-width"]).toBe("1px");
    expect(vars["--layer-3-border-width"]).toBe("1px");
    expect(contrastRatio(vars["--layer-2-edge"], ds.vars["--bg"])).toBeGreaterThanOrEqual(3);
    expect(contrastRatio(vars["--layer-2-edge"], vars["--layer-1-fill"])).toBeGreaterThanOrEqual(3);
  });

  it("Family Hub's Deep Space uses the same gray card hue and weight", () => {
    const block = hub.match(/\r?\n  deepspace: \{[\s\S]*?\r?\n  \},\r?\n/)?.[0] ?? "";
    const hues = block.match(/layers:\s*\{\s*hues:\s*\[([^\]]*)\]/)?.[1].match(/#[0-9a-fA-F]{6}/g);
    expect(hues).toEqual(ds.layers.hues.map(r => resolve(ds, r)));
    expect(block).toContain("widths: ['1px', '2px', '1px']");
  });
});

// Reported 2026-09-20: 80s Neon needs much more edge glow.
describe("80s Neon edge glow", () => {
  const vars = layerVarsFor(THEMES.neon80s);
  const alphas = (shadow) => [...shadow.matchAll(/rgba\([^)]*,\s*([\d.]+)\)/g)].map(m => +m[1]);
  const blurs = (shadow) => [...shadow.matchAll(/0 0 (\d+)px/g)].map(m => +m[1]);

  it("lights each layer with a tight bright halo plus wider blooms", () => {
    expect(alphas(vars["--layer-1-shadow"])[0]).toBeGreaterThanOrEqual(0.8);
    expect(blurs(vars["--layer-1-shadow"]).length).toBeGreaterThanOrEqual(3);
    expect(Math.max(...blurs(vars["--layer-1-shadow"]))).toBeGreaterThanOrEqual(36);
    expect(alphas(vars["--layer-2-shadow"])[0]).toBeGreaterThanOrEqual(0.65);
    expect(blurs(vars["--layer-2-shadow"]).length).toBeGreaterThanOrEqual(3);
    expect(alphas(vars["--layer-3-shadow"])[0]).toBeGreaterThanOrEqual(0.6);
  });

  it("glows brighter than every other theme's shadow shape would (only neon uses the glow shape)", () => {
    const glowThemes = Object.values(THEMES).filter(t => t.layers.shadow === "glow").map(t => t.id);
    expect(glowThemes).toEqual(["neon80s"]);
  });
});

describe("controls that are on, open or pressed", () => {
  it("carry the selection glow", () => {
    const rule = css.split("}").find(r => r.includes(".btn-ghost-active, .btn-secondary.active")) ?? "";
    expect(rule).toContain("var(--selected-glow, none)");
    expect(rule).toContain('button[aria-pressed="true"]');
  });
});

describe("selected rows keep their theme's shadow", () => {
  it("never leaves a bare 'none' where a selected row layers its bar on top", () => {
    for (const theme of Object.values(THEMES)) {
      const v = layerVarsFor(theme);
      for (const k of Object.keys(v).filter(k => k.endsWith("-shadow"))) expect(v[k], `${theme.id} ${k}`).not.toBe("none");
    }
  });

  it("80s Neon's selected row falls back to the strongest layer glow rather than losing its own", () => {
    const v = layerVarsFor(THEMES.neon80s);
    expect(v["--layer-selected-shadow"]).toBe(v["--layer-1-shadow"]);
    expect(THEMES.neon80s.vars["--selected-glow"]).toBeUndefined();
  });
});
