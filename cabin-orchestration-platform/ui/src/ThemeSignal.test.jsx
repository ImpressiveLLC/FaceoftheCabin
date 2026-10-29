// The Signal theme (2026-10-29): the HA wall-panel look from Nate's reference dashboards. These tests pin what makes it Signal and
// keep the two apps in step: the same tokens in both, fonts bundled (never fetched), the cue block shared, and the Signal rules
// in place after it. Visual fidelity is checked in a browser, not here.
import React from "react";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { readFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { ThemeProvider, THEMES } from "./ThemeProvider.jsx";
import { familyHubThemes } from "./attentionTokens.js";

const here = dirname(fileURLToPath(import.meta.url));
const read = (rel) => readFileSync(resolve(here, rel), "utf8");
const cabinCss = read("styles.css");
const hubHtml = read("../../../family-hub/family-hub.html");
const hub = familyHubThemes(hubHtml);
const FONT_DIRS = ["ibm-plex-mono", "syne", "outfit"]; // Syne stays bundled (Gauss-style card titles); Outfit now carries titles

afterEach(() => {
  cleanup();
  localStorage.clear();
  document.documentElement.removeAttribute("style");
  document.documentElement.removeAttribute("data-theme");
  document.body.removeAttribute("style");
  document.head.querySelectorAll("link[id^='cabin-font-']").forEach((el) => el.remove());
});

describe("Signal palette and type", () => {
  const v = THEMES.signal?.vars;

  it("exists in both apps under the same id and label", () => {
    expect(THEMES.signal.label).toBe("Signal");
    expect(hub.signal).toBeTruthy();
    expect(hubHtml).toMatch(/label: 'Signal', id: 'signal'/);
  });

  it("is a blue-black ground with one neon-green accent that is also the ok colour", () => {
    expect(v["--bg"]).toBe("#04070f");
    expect(v["--bg-secondary"]).toBe("#0a0e17");
    expect(v["--accent"]).toBe("#39ff14");
    expect(v["--success"]).toBe(v["--accent"]);
    expect(v["--warning"]).not.toBe(v["--accent"]);
    expect(v["--danger"]).not.toBe(v["--accent"]);
  });

  it("carries the same accent, ground and attention hues in Family Hub", () => {
    const h = hub.signal.vars;
    expect(h["--night"]).toBe(v["--bg"]);
    expect(h["--gold"]).toBe(v["--accent"]);
    expect(h["--teal"]).toBe(v["--success"]);
    expect(h["--rose"]).toBe(v["--danger"]);
    for (const k of Object.keys(v).filter((x) => x.startsWith("--attn-"))) expect(h[k], k).toBe(v[k]);
    expect(h["--signal-heat"]).toBe(v["--signal-heat"]);
    expect(h["--signal-cool"]).toBe(v["--signal-cool"]);
  });

  it("uses Plex Mono for readings and Outfit for titles and labels", () => {
    expect(v["--font-mono"]).toMatch(/^'IBM Plex Mono'/);
    expect(v["--font-display"]).toMatch(/^'Outfit'/);
    expect(v["--font-ui"]).toMatch(/^'Outfit'/);
    // The hub's table quotes font stacks with double quotes, which familyHubThemes() does not read, so match its own text.
    const chunk = hubHtml.slice(hubHtml.indexOf("label: 'Signal', id: 'signal'"), hubHtml.indexOf("fonts: [],", hubHtml.indexOf("label: 'Signal', id: 'signal'")));
    expect(chunk).toMatch(/'--font-mono':"'IBM Plex Mono'/);
    expect(chunk).toMatch(/'--font-display':"'Outfit'/);
    expect(chunk).toMatch(/'--font-ui':"'Outfit'/);
  });

  it("stamps data-theme and its fonts when selected, and fetches nothing from a font CDN", () => {
    localStorage.setItem("cabin-theme", "signal");
    render(<ThemeProvider><span /></ThemeProvider>);
    expect(document.documentElement.getAttribute("data-theme")).toBe("signal");
    expect(document.documentElement.style.getPropertyValue("--accent")).toBe("#39ff14");
    expect(document.head.querySelectorAll("link[id^='cabin-font-']")).toHaveLength(0);
    expect(hub.signal.fonts ?? []).toHaveLength(0);
  });
});

describe("Signal fonts are bundled in both apps", () => {
  const cabinFonts = "../public/fonts";
  const hubFonts = "../../../family-hub/fonts";

  it("ships the OFL licence beside each family in both apps", () => {
    for (const root of [cabinFonts, hubFonts]) {
      for (const dir of FONT_DIRS) {
        expect(existsSync(resolve(here, root, dir, "OFL.txt")), `${root}/${dir}/OFL.txt`).toBe(true);
      }
    }
  });

  it("declares every face in retro-fonts.css (identical in both apps) and each referenced file exists in both", () => {
    const cabinCssFonts = read(`${cabinFonts}/retro-fonts.css`);
    expect(read(`${hubFonts}/retro-fonts.css`)).toBe(cabinCssFonts);
    const urls = [...cabinCssFonts.matchAll(/url\('\.\/((?:ibm-plex-mono|syne|outfit)\/[^']+\.woff2)'\)/g)].map((m) => m[1]);
    expect(urls.length).toBe(11); // Plex Mono 400/500/600, Syne 600/700, Outfit 200/300/400/500/600/700
    for (const root of [cabinFonts, hubFonts]) {
      for (const f of urls) expect(existsSync(resolve(here, root, f)), `${root}/${f}`).toBe(true);
    }
  });
});

describe("Signal styling lives after the shared attention cues and stays inside the rules", () => {
  const cuesEnd = cabinCss.indexOf("/* </attention-cues> */");
  const signalAt = cabinCss.indexOf('SIGNAL THEME ([data-theme="signal"])');
  const signalCss = cabinCss.slice(signalAt);
  const hubStart = hubHtml.indexOf('SIGNAL THEME ([data-theme="signal"])');
  const hubSignal = hubHtml.slice(hubStart, hubHtml.indexOf("/* Tab buttons:", hubStart));

  it("sits after the cue block so it only restyles what the cues leave open", () => {
    expect(cuesEnd).toBeGreaterThan(0);
    expect(signalAt).toBeGreaterThan(cuesEnd);
    expect(hubHtml.indexOf('SIGNAL THEME ([data-theme="signal"])')).toBeGreaterThan(hubHtml.indexOf("/* </attention-cues> */"));
  });

  it("has no animation or pulse anywhere in Signal (MA-16)", () => {
    for (const css of [signalCss, hubSignal]) {
      expect(css).not.toMatch(/@keyframes|animation\s*:|transition\s*:/);
      expect(css).not.toMatch(/filter\s*:\s*blur/);
    }
  });

  // Nate 2026-10-29: glow is allowed in normal use (MA-16 only rules out pulsing and says "no glow" is not needed).
  const glowOf = (css) => {
    const a = css.indexOf("/* <signal-glow> */"), b = css.indexOf("/* </signal-glow> */");
    return a >= 0 && b > a ? css.slice(a, b) : "";
  };
  const withoutGlow = (css) => css.replace(glowOf(css), "");

  it("keeps glow in one marked section, static, and off for more-contrast and forced-colors", () => {
    for (const css of [signalCss, hubSignal]) {
      const g = glowOf(css);
      expect(g.length, "glow section present").toBeGreaterThan(0);
      expect(g).toMatch(/@media \(not \(prefers-contrast: more\)\) and \(not \(forced-colors: active\)\)/);
      expect(g).toMatch(/text-shadow|box-shadow/);
      expect(g).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    }
  });

  it("gives no glow to Watch or Alert, so the attention cues stay the only loud signal", () => {
    for (const css of [signalCss, hubSignal]) {
      for (const rule of glowOf(css).matchAll(/([^{}]+)\{[^}]*\}/g)) {
        expect(rule[1], "glow selector").not.toMatch(/kpi-state-(warn|alarm|late|unknown)|state-(warn|alarm|late|unknown)/);
      }
    }
  });

  it("outside the glow section Signal only clears shadows", () => {
    for (const css of [signalCss, hubSignal]) {
      const rest = withoutGlow(css);
      const shadows = [...rest.matchAll(/box-shadow\s*:\s*([^;]+);/g)].map((m) => m[1].trim());
      for (const s of shadows) expect(s, "only `none` outside the glow section").toBe("none");
      expect(rest).not.toMatch(/text-shadow\s*:\s*(?!\s*none)/);
    }
  });

  it("takes its colours from tokens, not hard-coded hex or rgb", () => {
    expect(signalCss).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });

  it("keeps the state word and icon on status chips: it never hides the chip's ::before or its text", () => {
    expect(signalCss).not.toMatch(/state-badge[^{]*::before\s*\{[^}]*display\s*:\s*none/);
    expect(signalCss).not.toMatch(/\.state-badge[^{]*\{[^}]*(font-size\s*:\s*0|color\s*:\s*transparent)/);
  });

  it("gives Normal a quiet mix of its hue but leaves the Watch and Alert borders to the cue block", () => {
    expect(signalCss).toMatch(/kpi-state-ok\s*\{\s*border-color:\s*color-mix\(in srgb, var\(--attn-normal\)/);
    expect(signalCss).not.toMatch(/kpi-state-warn[^{]*\{[^}]*border/);
    expect(signalCss).not.toMatch(/kpi-state-alarm[^{]*\{[^}]*border/);
  });
});
