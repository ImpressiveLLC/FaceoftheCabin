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
        // A selector may name Watch and Alert only to exclude them.
        const included = rule[1].replace(/:not\(\.kpi-state-(warn|alarm)\)/g, "");
        expect(included, "glow selector").not.toMatch(/kpi-state-(warn|alarm|late|unknown)|state-(warn|alarm|late|unknown)/);
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

  it("W-124: the glow on a tile's reading and bar excludes Watch and Alert tiles", () => {
    const g = glowOf(signalCss);
    expect(g).toMatch(/\.kpi-tile:not\(\.kpi-state-warn\):not\(\.kpi-state-alarm\) \.kpi-value \{ text-shadow/);
    expect(g).toMatch(/\.kpi-tile:not\(\.kpi-state-warn\):not\(\.kpi-state-alarm\)::before \{ box-shadow/);
  });

  it("W-124: a Watch or Alert tile keeps its own hue over the calm metric hue (the :has() metric rules carry no specificity)", () => {
    for (const m of signalCss.matchAll(/^\[data-theme="signal"\] \.kpi-tile(:[^{]*)\{ --signal-metric/gm)) {
      if (m[1].includes(":has(")) expect(m[1], "metric :has() wrapped").toMatch(/^:where\(:has\(.*\)\) $/);
    }
    expect(signalCss).toMatch(/\.kpi-tile\.kpi-state-warn\s+\{ --signal-metric: var\(--warning\)/);
    expect(signalCss).toMatch(/\.kpi-tile\.kpi-state-alarm \{ --signal-metric: var\(--danger\)/);
  });

  it("W-124: in forced colours a tile is Canvas with CanvasText reading, label, note, icon and underline", () => {
    // The cue block sets forced-color-adjust: none on status tiles, so system colours are not applied for us: say them.
    const forced = [...signalCss.matchAll(/@media \(forced-colors: active\) \{([^@]*?)\n\}/g)].map((m) => m[1]).join("\n");
    expect(forced).toMatch(/\.kpi-tile \{ background: Canvas; \}/);
    expect(forced).toMatch(/\.kpi-tile :is\(\.kpi-value, \.kpi-value-note, \.kpi-label\)[^{]*\.kpi-tile > svg \{ color: CanvasText; \}/);
    expect(forced).toMatch(/\.kpi-tile::before \{ background: CanvasText; \}/);
  });

  it("W-124: reading and chip hues are set only outside forced colours, so a light Canvas stays readable", () => {
    const nonForced = signalCss.match(/@media \(not \(forced-colors: active\)\) \{[^@]*?\n\}/);
    expect(nonForced, "scoped colour block present").toBeTruthy();
    expect(nonForced[0]).toMatch(/\.kpi-value \{ color: var\(--signal-metric\)/);
    // Outside that block no .kpi-value or .state-badge rule sets a text colour.
    // The forced-colours block is where the system colours are set on purpose (checked by the test above).
    const rest = signalCss.replace(nonForced[0], "").replace(/@media \(forced-colors: active\) \{[^@]*?\n\}/g, "");
    for (const rule of rest.matchAll(/(\[data-theme="signal"\][^{}]*(?:\.kpi-value|\.state-badge)[^{}]*)\{([^}]*)\}/g)) {
      expect(rule[2], rule[1].trim()).not.toMatch(/(^|[;\s])color\s*:/);
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

describe("Signal surfaces: pure black fills, no grey hairlines (Nate 2026-10-29)", () => {
  const sectionOf = (css, name) => {
    const a = css.indexOf(`/* <${name}> */`), b = css.indexOf(`/* </${name}> */`);
    return a >= 0 && b > a ? css.slice(a, b) : "";
  };
  const cabin = sectionOf(cabinCss, "signal-surfaces");

  it("cabin: big frames float (no fill, no border colour), mid and small boxes get a coloured edge", () => {
    expect(cabin.length, "surfaces section present").toBeGreaterThan(0);
    expect(cabin).toMatch(/\.sensor-history-panel, \.event-log\) \{ background: transparent; border-color: transparent; \}/);
    expect(cabin).toMatch(/\.dm-device-row[\s\S]*?\{\s*background: transparent; border-color: color-mix\(in srgb, var\(--hue, var\(--signal-cool\)\) 65%, transparent\)/);
    expect(cabin).not.toMatch(/var\(--border\)|var\(--bg-secondary\)|var\(--surface\)|var\(--layer-/);
    expect(cabin).not.toMatch(/#[0-9a-fA-F]{3,8}\b|animation|box-shadow|@keyframes/);
    // The tiles' own status borders are left to the cue block.
    expect(cabin).not.toMatch(/\.kpi-tile|\.state-badge/);
  });

  it("cabin: state-bearing rows keep their Alert and Watch edge colours (W-109/W-110)", () => {
    const body = cabin.replace(/\/\*[\s\S]*?\*\//g, "");
    const rules = [...body.matchAll(/([^{}]+)\{([^}]*)\}/g)].map((m) => ({ sel: m[1].trim(), decl: m[2] }));
    expect(rules.length).toBeGreaterThan(5);
    for (const { sel, decl } of rules) {
      // Nothing that sets a whole border colour may name a cue-bearing class unless it excludes the critical state.
      if (/(^|[\s,(])border-color\s*:/.test(decl) || /border-color/.test(decl.replace(/border-(top|right|bottom)-color/g, ""))) {
        expect(sel, "border-color on a cue class").not.toMatch(/\.active-condition(?!s|-critical)|\.automation-alert-card|\.cabin-activity-warning|data-attn/);
      }
    }
    // The only rule that colours those rows' edges leaves the left edge alone and excludes the critical class.
    expect(cabin).toMatch(/\.active-condition:not\(\.active-condition-critical\)/);
    expect(cabin).toMatch(/\.automation-alert-card:not\(\.automation-alert-critical\)/);
    expect(cabin).not.toMatch(/border-left-color|\.active-condition-critical \{|\.automation-alert-critical \{/);
  });

  it("cabin: Monitoring only takes the Sensor History and live-log frames and the unselected chips and tabs", () => {
    const body = cabin.replace(/\/\*[\s\S]*?\*\//g, "");
    const selectors = [...body.matchAll(/([^{}]+)\{/g)].map((m) => m[1].trim()).filter(Boolean);
    const open = selectors.filter((sel) => !/:not\(\[data-panel="monitoring"\] \*\)/.test(sel));
    expect(open.every((sel) => /\.sensor-history-panel|\.event-log/.test(sel)), `unscoped: ${open.join(" | ")}`).toBe(true);
    expect(cabin).toMatch(/\.sensor-history-device-chip:not\(\.selected\)/);
    expect(cabin).toMatch(/\.sensor-history-topic-tab:not\(\.active\)/);
    expect(cabin).not.toMatch(/alert-ctrl/);
  });

  it("W-125: no surfaces rule sets a background on the critical (cue-block) rows, and the Sensor History header stays opaque", () => {
    const body = cabin.replace(/\/\*[\s\S]*?\*\//g, "");
    for (const m of body.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
      if (!/background\s*:/.test(m[2])) continue;
      const sel = m[1].replace(/\.(active-condition|automation-alert-card):not\(\.(active-condition|automation-alert)-critical\)/g, "");
      expect(sel, "background on a selector that reaches a critical row").not.toMatch(/(?<![-\w])\.active-condition(?![-\w])|\.automation-alert-card(?![-\w])|-critical/);
    }
    expect(cabin).not.toMatch(/sensor-history-table th/);
  });

  it("hub: cards are the page black with no hairline, and small boxes have no grey fill", () => {
    const hub = sectionOf(hubHtml, "signal-roles");
    expect(hub).toMatch(/:is\(\.glass-card, #notepad-panel, #settings-panel\) \{ background: var\(--night\); border-color: transparent; \}/);
    expect(hub).toMatch(/\.bday-item[^{]*\{\s*background: transparent; border-color: color-mix\(in srgb, var\(--hue/);
  });
});

// W-126 item 1 asked for a computed-style check beside the CSS-source tests. jsdom cannot give one here: it ignores var(), :has() and
// pseudo-elements, and its cascade for these long selector lists proved order-dependent (the same assertion passed and failed
// between runs), so a green check would not mean what it says. The two W-126 fixes below are therefore pinned at the source with
// whitespace-tolerant patterns, and were measured in a real Chromium page (see the PR): the Alert icon overlap went from 6 x 6 px
// to 0, and the critical-row detail text from 3.96:1 to 4.93:1.
describe("Signal W-126 fixes (source pins; measured in Chromium, see the PR)", () => {
  const compact = cabinCss.replace(/\s+/g, " ");

  it("an Alert tile's icon sits clear of the corner asterisks (20 px or more from the top and right)", () => {
    const m = compact.match(/\.kpi-tile\.kpi-state-alarm > svg \{ top: (\d+)px; right: (\d+)px; \}/);
    expect(m, "alarm icon rule present").toBeTruthy();
    expect(Number(m[1])).toBeGreaterThanOrEqual(20);
    expect(Number(m[2])).toBeGreaterThanOrEqual(20);
  });

  it("row detail text uses the muted text token in Signal, not the dimmer one that measured 3.96:1", () => {
    expect(compact).toMatch(/\[data-theme="signal"\] \.active-condition p \{ color: var\(--text-muted\); \}/);
  });
});
