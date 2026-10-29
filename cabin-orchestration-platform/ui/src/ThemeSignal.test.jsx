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

describe("Signal colour roles reach every tab except Monitoring (Nate 2026-10-29)", () => {
  const rolesOf = (css) => {
    const a = css.indexOf("/* <signal-roles> */"), b = css.indexOf("/* </signal-roles> */");
    return a >= 0 && b > a ? css.slice(a, b) : "";
  };
  const appJsx = read("App.jsx");
  const cabinRoles = rolesOf(cabinCss);
  const hubRoles = rolesOf(hubHtml);

  it("defines the five roles once, from the existing palette tokens, in both apps", () => {
    for (const css of [cabinRoles, hubRoles]) {
      expect(css.length, "roles section present").toBeGreaterThan(0);
      for (const r of ["--role-on", "--role-select", "--role-edit", "--role-pending", "--role-danger"]) expect(css, r).toContain(r + ":");
      expect(css).toMatch(/--role-select:\s*var\(--signal-cool\)/);
      expect(css).toMatch(/--role-edit:\s*var\(--signal-heat\)/);
      expect(css).toMatch(/--role-pending:\s*var\(--signal-amber\)/);
    }
  });

  it("uses tokens only, no animation, and shadows only inside the glow section", () => {
    for (const css of [cabinRoles, hubRoles]) {
      expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
      expect(css).not.toMatch(/@keyframes|animation\s*:|transition\s*:/);
      expect(css).not.toMatch(/box-shadow\s*:\s*(?!\s*none)|text-shadow\s*:\s*(?!\s*none)/);
    }
  });

  it("colours Device Manager modes by job: See cyan, Change orange, Add lime, Remove red", () => {
    expect(cabinRoles).toMatch(/\[data-panel="devices"\] \.dm-l1-btn \{ --mode: var\(--role-select\)/);
    expect(cabinRoles).toMatch(/\.dm-l1-btn:nth-child\(2\) \{ --mode: var\(--role-edit\)/);
    expect(cabinRoles).toMatch(/\.dm-l1-btn:nth-child\(3\) \{ --mode: var\(--role-on\)/);
    expect(cabinRoles).toMatch(/\.dm-l1-btn:nth-child\(4\) \{ --mode: var\(--role-danger\)/);
  });

  it("marks the selected device row with more than a hue: a thick left edge as well as cyan", () => {
    expect(cabinRoles).toMatch(/\.dm-device-row\.dm-row-selected \{[^}]*border-left-width:\s*5px/);
    expect(cabinRoles).toMatch(/\.dm-device-row\.dm-row-selected \{[^}]*--role-select/);
  });

  it("leaves Monitoring alone: every role rule is scoped to a panel other than Monitoring, or excludes it", () => {
    expect(cabinRoles.length, "roles section present").toBeGreaterThan(0);
    const body = cabinRoles.replace(/\/\*[\s\S]*?\*\//g, "");
    const selectors = [...body.matchAll(/([^{}@;]+)\{/g)].map((m) => m[1].trim()).filter((x) => x && !x.startsWith("media "));
    expect(selectors.length).toBeGreaterThan(20);
    const unscoped = selectors.filter((sel) => sel !== '[data-theme="signal"]' && !/\[data-panel/.test(sel) && !/:not\(\[data-panel="monitoring"\] \*\)/.test(sel));
    expect(unscoped, "role rules that could reach Monitoring").toEqual([]);
    // Any mention of Monitoring's panel is an exclusion, never a target.
    for (const m of cabinRoles.matchAll(/data-panel="monitoring"/g)) expect(cabinRoles.slice(Math.max(0, m.index - 8), m.index)).toMatch(/:not\(\[$/);
    expect(cabinRoles).not.toMatch(/\.kpi-|\.state-badge|\.location-section/);
    // The page-wide accent colour must not be set on the root (it would colour Monitoring's checkbox).
    expect(cabinRoles).not.toMatch(/\[data-theme="signal"\] \{[^}]*accent-color/);
  });

  it("gives every panel root a data-panel hook, and Monitoring has its own", () => {
    for (const name of ["camera", "opportunities", "helpdesk", "places", "config", "devices", "monitoring", "rules"]) {
      expect(appJsx, name).toContain(`data-panel="${name}"`);
    }
  });

  it("keeps the focus ring cyan on every control outside Monitoring, and gives way to the system colours in forced colours", () => {
    for (const css of [cabinRoles, hubRoles]) {
      expect(css.length, "roles section present").toBeGreaterThan(0);
      expect(css).toMatch(/@media \(not \(forced-colors: active\)\) \{\s*\[data-theme="signal"\] :is\(a, button, summary, \[role="button"\], \[tabindex\]\):focus-visible[^{]*\{\s*outline: 2px solid var\(--role-select\)/);
      // Fields lose their UA outline when focused, so forced colours get one back.
      expect(css).toMatch(/@media \(forced-colors: active\) \{[^}]*:focus-visible \{ outline: 2px solid Highlight/);
    }
    expect(cabinRoles).toMatch(/:focus-visible:not\(\[data-panel="monitoring"\] \*\)/);
  });

  it("keeps the glow's field selector the same as the field rule's", () => {
    expect(cabinCss).toMatch(/:is\(input:not\(\[type="checkbox"\]\):not\(\[type="radio"\]\):not\(\[type="range"\]\), select:not\(\.presence-select\), textarea\):focus \{ box-shadow/);
  });
});
