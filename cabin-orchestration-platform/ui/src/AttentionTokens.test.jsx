import { describe, it, expect } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { THEMES } from "./ThemeProvider.jsx";
import {
  ATTN_STATES, attnVar, attnInkVar, SURFACES, THRESHOLDS, DEFICIENCIES,
  simulate, deltaE, deltaE00FromLab, evaluateTheme, attentionReport, attentionProblems, formatReport, familyHubThemes,
} from "./attentionTokens.js";

const hubHtmlPath = [path.join(path.dirname(fileURLToPath(import.meta.url)), "../../../family-hub/family-hub.html"), "/family-hub/family-hub.html"]
  .find(existsSync);
const hubThemes = hubHtmlPath ? familyHubThemes(readFileSync(hubHtmlPath, "utf8")) : null;

// Themes with no --attn-* tokens yet. W-110 landed tokens for all eleven themes in both surfaces, so this is empty. It stays as an explicit
// list, written out on purpose and NOT derived from THEMES, so a theme added later fails the gate until someone either gives it tokens or
// adds it here with a reason.
const PENDING_W110 = [];

const good = {
  id: "synthetic",
  vars: {
    "--bg": "#0d1117", "--bg-secondary": "#161b22",
    "--attn-normal": "#2093e8", "--attn-normal-ink": "#0b0f14",
    "--attn-watch": "#fbe729", "--attn-watch-ink": "#0b0f14",
    "--attn-alert": "#f75c3e", "--attn-alert-ink": "#0b0f14",
  },
};
const withVars = (patch) => ({ ...good, vars: { ...good.vars, ...patch } });

describe("colour maths", () => {
  it("simulation leaves neutral greys alone and moves saturated colours", () => {
    for (const d of DEFICIENCIES) {
      const grey = simulate("#808080", d);
      grey.forEach((c) => expect(Math.abs(c - 128)).toBeLessThan(2));
    }
    const [r, g] = simulate("#00ff00", "deuteranopia");
    expect(Math.abs(r - 0)).toBeGreaterThan(20); // pure green is not still pure green
    expect(g).toBeLessThan(255);
  });
  it("deltaE is 0 for equal colours and large for black against white", () => {
    expect(deltaE([10, 20, 30], [10, 20, 30])).toBe(0);
    expect(deltaE([0, 0, 0], [255, 255, 255])).toBeGreaterThan(95);
  });
});

describe("CIEDE2000", () => {
  // Reference pairs from Sharma, Wu and Dalal (2005), Table 1.
  it("matches the published reference values", () => {
    expect(deltaE00FromLab([50, 2.6772, -79.7751], [50, 0, -82.7485])).toBeCloseTo(2.0425, 4);
    expect(deltaE00FromLab([50, 3.1571, -77.2803], [50, 0, -82.7485])).toBeCloseTo(2.8615, 4);
    expect(deltaE00FromLab([50, 2.5, 0], [73, 25, -18])).toBeCloseTo(27.1492, 4);
    expect(deltaE00FromLab([50, 2.5, 0], [50, 3.2972, 0])).toBeCloseTo(1.0, 4);
  });
  it("flags the Modern theme's own green, amber and red as indistinguishable under deuteranopia", () => {
    const m = THEMES.modern.vars;
    const r = evaluateTheme(withVars({ "--attn-normal": m["--success"], "--attn-watch": m["--warning"], "--attn-alert": m["--danger"] }));
    expect(r.status).toBe("unsupported");
    expect(r.hueFailures.join(" ")).toMatch(/under deuteranopia: deltaE00/);
  });
});

describe("evaluateTheme", () => {
  it("supports a theme whose tokens meet every threshold", () => {
    const r = evaluateTheme(good);
    expect(r.failures).toEqual([]);
    expect(r.status).toBe("supported");
  });

  it("reports no-tokens, naming each missing token, when a theme has none", () => {
    const r = evaluateTheme({ id: "bare", vars: { "--bg": "#0d1117", "--bg-secondary": "#161b22" } });
    expect(r.status).toBe("no-tokens");
    expect(r.missing).toContain("--attn-alert");
    expect(r.missing).toContain("--attn-watch-ink");
  });

  it("fails weak ink, a faint edge and hues that collapse under colour-vision simulation", () => {
    const ink = evaluateTheme(withVars({ "--attn-watch-ink": "#e3c060" }));
    expect(ink.status).toBe("fail");
    expect(ink.failures.join(" ")).toMatch(/watch: ink on tint/);
    const edge = evaluateTheme(withVars({ "--bg-secondary": "#e3b341" }));
    expect(edge.failures.join(" ")).toMatch(/watch: edge on surface/);
    // the worse of the two surfaces counts: a fine card face does not rescue a page background the edge disappears into
    const page = evaluateTheme(withVars({ "--bg": "#f2cc60" }));
    expect(page.failures.join(" ")).toMatch(/watch: edge on surface/);
    const same = evaluateTheme(withVars({ "--attn-watch": good.vars["--attn-normal"] }));
    expect(same.status).toBe("unsupported");
    expect(same.hueFailures.join(" ")).toMatch(/normal vs watch under (deuteranopia|protanopia|tritanopia)/);
  });

  it("states the hue threshold is provisional until W-109 fixes it", () => {
    expect(THRESHOLDS.provisional).toContain("hueDeltaE00");
    expect(THRESHOLDS.provisional).toContain("lightnessGap");
    expect(THRESHOLDS.lightnessGap).toBe(20);
    expect(THRESHOLDS.hueDeltaE00).toBe(10);
    expect(THRESHOLDS.textOnTint).toBe(4.5);
    expect(THRESHOLDS.edgeOnSurface).toBe(3);
  });
});

describe("the gate over the real themes", () => {
  const report = attentionReport(THEMES);

  it("covers all eleven themes and a theme with tokens never fails silently", () => {
    expect(report).toHaveLength(11);
    expect(report.map((r) => r.id).sort()).toEqual(Object.keys(THEMES).sort());
    expect(report.filter((r) => r.status === "fail" || r.status === "partial")).toEqual([]);
  });

  it("keeps the pending list honest: every entry is a real theme, and the list is independent of THEMES", () => {
    for (const id of PENDING_W110) expect(Object.keys(THEMES)).toContain(id);
    expect(new Set(PENDING_W110).size).toBe(PENDING_W110.length);
  });

  it("passes with an empty pending list: every theme in the Cabin UI carries a complete, compliant token set", () => {
    expect(report.filter((r) => r.status === "no-tokens")).toEqual([]);
    expect(attentionProblems(report, PENDING_W110)).toEqual([]);
    expect(attentionProblems(report, [])).toEqual([]);
  });

  it("nine themes are supported; Asteroid City is unsupported on lightness only, and routes to the strongest-weight cues", () => {
    const byId = Object.fromEntries(report.map((r) => [r.id, r]));
    for (const id of Object.keys(THEMES).filter((t) => t !== "asteroidcity")) expect(byId[id].status, id).toBe("supported");
    expect(byId.asteroidcity.status).toBe("unsupported");
    expect(byId.asteroidcity.failures).toEqual([]);
    expect(byId.asteroidcity.hueFailures.every((f) => /lightness gap/.test(f))).toBe(true);
    expect(THEMES.asteroidcity.vars["--attn-cue-scale"]).toBe("1.5");
    for (const id of Object.keys(THEMES).filter((t) => t !== "asteroidcity")) expect(THEMES[id].vars["--attn-cue-scale"], id).toBe("1");
  });

  it("fails without the tokens: stripping every --attn-* from the same themes breaks the build for all eleven", () => {
    const stripped = Object.fromEntries(Object.entries(THEMES).map(([id, t]) => [id,
      { ...t, vars: Object.fromEntries(Object.entries(t.vars).filter(([k]) => !k.startsWith("--attn-"))) }]));
    const problems = attentionProblems(attentionReport(stripped), []);
    expect(problems).toHaveLength(11);
    expect(problems[0]).toMatch(/no attention tokens .* not on the pending list/);
  });

  it("a theme added later and not on the pending list fails the gate", () => {
    const withNew = attentionReport({ ...THEMES, brandnew: { id: "brandnew", vars: { "--bg-secondary": "#101010" } } });
    expect(attentionProblems(withNew, PENDING_W110)).toHaveLength(1);
    expect(attentionProblems(withNew, PENDING_W110)[0]).toMatch(/^brandnew:/);
  });

  it("a hue-only miss marks the theme unsupported and is not a build problem (W-109 ruling), but contrast misses still are", () => {
    const white = { "--bg": "#000000", "--bg-secondary": "#000000", "--attn-normal": "#ffffff", "--attn-watch": "#ffffff", "--attn-alert": "#ffffff",
      "--attn-normal-ink": "#000000", "--attn-watch-ink": "#000000", "--attn-alert-ink": "#000000" };
    const hueOnly = evaluateTheme({ id: "synthetic", vars: white });
    expect(hueOnly.failures).toEqual([]);
    expect(hueOnly.hueFailures.length).toBeGreaterThan(0);
    expect(hueOnly.status).toBe("unsupported");
    expect(attentionProblems([hueOnly], [])).toEqual([]);
    const both = evaluateTheme({ id: "synthetic", vars: { ...white, "--attn-alert-ink": "#ffffff" } });
    expect(both.status).toBe("fail");
    expect(attentionProblems([both], [])).toHaveLength(1);
    expect(formatReport([hueOnly])).toMatch(/\| synthetic \| cabin \| unsupported \|/);
  });

  it("fails a themed token set that misses a threshold, even when the theme is on the pending list", () => {
    const broken = [evaluateTheme(withVars({ "--attn-alert-ink": "#ff8f86" }))];
    expect(attentionProblems(broken, ["synthetic"])).toHaveLength(1);
  });

  it("a partial set cannot hide behind the pending list: one token missing and another failing is a problem", () => {
    const partial = evaluateTheme({ id: "synthetic", vars: { ...good.vars, "--attn-watch-ink": undefined, "--attn-alert-ink": "#ff8f86" } });
    expect(partial.status).toBe("partial");
    expect(partial.failures.join(" ")).toMatch(/incomplete token set, missing --attn-watch-ink/);
    expect(partial.failures.join(" ")).toMatch(/alert: ink on tint/);
    expect(attentionProblems([partial], ["synthetic"])).toHaveLength(1);
  });

  it("reports the Family Hub surface separately, per theme, with the same outcome as the Cabin UI", () => {
    expect(hubThemes, "family-hub/family-hub.html must be reachable: Family Hub is a required W-111 surface").not.toBeNull();
    const hubReport = attentionReport(hubThemes, "hub");
    expect(hubReport.map((r) => r.id).sort()).toEqual(Object.keys(THEMES).sort());
    expect(hubReport.every((r) => r.surface === "hub")).toBe(true);
    expect(hubReport.filter((r) => r.status === "no-tokens")).toEqual([]);
    expect(attentionProblems(hubReport, [])).toEqual([]);
    expect(Object.fromEntries(hubReport.map((r) => [r.id, r.status]))).toEqual(Object.fromEntries(report.map((r) => [r.id, r.status])));
    expect(SURFACES.hub).toEqual(["--night"]);
    const text = formatReport([...report, ...hubReport]);
    expect(text).toMatch(/\| modern \| cabin \|/);
    expect(text).toMatch(/\| modern \| hub \|/);
  });

  it("gives Family Hub exactly the same attention values as the Cabin UI, so a theme reads the same in both apps", () => {
    expect(hubThemes).not.toBeNull();
    const keys = [...ATTN_STATES.flatMap((s) => [attnVar(s), attnInkVar(s)]), "--attn-cue-scale"];
    for (const id of Object.keys(THEMES)) for (const k of keys) expect(hubThemes[id].vars[k], `${id} ${k}`).toBe(THEMES[id].vars[k]);
  });

  it("extracts Family Hub variables from its inline theme table", () => {
    expect(hubThemes, "family-hub/family-hub.html must be reachable: Family Hub is a required W-111 surface").not.toBeNull();
    expect(hubThemes.modern.vars["--night"]).toMatch(/^#[0-9a-f]{6}$/i);
    expect(Object.keys(hubThemes)).toHaveLength(11);
  });

  it("reports no pending themes, never claims a fallback palette, and still lists what was not run", () => {
    const text = formatReport(report);
    expect(text).not.toMatch(/pending W-110/);
    expect(text).not.toMatch(/fallback/i);
    expect(text).toMatch(/NOT_RUN/);
  });

  it("produces a per-theme report for each run", () => {
    const text = formatReport(report);
    for (const id of Object.keys(THEMES)) expect(text).toContain(`| ${id} |`);
    expect(ATTN_STATES.map(attnVar)).toEqual(["--attn-normal", "--attn-watch", "--attn-alert"]);
    expect(ATTN_STATES.map(attnInkVar)[2]).toBe("--attn-alert-ink");
  });
});
