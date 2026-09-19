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

  it("keeps orange as the primary: accent, focus and tabs", () => {
    for (const hex of [lcars.vars["--accent"], lcars.vars["--border-focus"]]) {
      expect(hueOf(hex)).toBeGreaterThan(25);
      expect(hueOf(hex)).toBeLessThan(45);
    }
    expect(resolve(lcars, lcars.layers.tab)).toBe(lcars.vars["--accent"]);
    expect(layerVarsFor(lcars)["--tab-color"]).toBe(lcars.vars["--accent"]);
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

  it("Family Hub's LCARS uses the same three layer hues and orange tabs", () => {
    const block = hub.match(/\r?\n  lcars: \{[\s\S]*?\r?\n  \},\r?\n/)?.[0] ?? "";
    const hues = block.match(/layers:\s*\{\s*hues:\s*\[([^\]]*)\]/)?.[1].match(/#[0-9a-fA-F]{6}/g);
    expect(hues).toEqual(lcars.layers.hues.map(r => resolve(lcars, r)));
    expect(block).toContain(`tab: '${lcars.vars["--accent"]}'`);
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

  it("is applied to selected cards, rows, chips and topic tabs, and falls back to the old shadow", () => {
    const rule = (needle) => css.split("}").find(r => r.includes(needle)) ?? "";
    expect(rule(".device-card.selected, .dm-row-selected")).toContain("var(--selected-glow, var(--layer-selected-shadow))");
    expect(rule(".sensor-history-topic-tab.active, .sensor-history-device-chip.selected")).toContain("var(--selected-glow, none)");
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
