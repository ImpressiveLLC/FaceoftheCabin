import { describe, it, expect } from "vitest";
import { THEMES } from "./ThemeProvider.jsx";
import {
  ATTN_STATES, attnVar, attnInkVar, SURFACE_VAR, THRESHOLDS, DEFICIENCIES,
  simulate, deltaE, evaluateTheme, attentionReport, attentionProblems, formatReport,
} from "./attentionTokens.js";

// Themes whose status layer falls back to the compliant palette until W-110 adds their --attn-* tokens.
// Shrink this list as tokens land; the build fails if a theme is on neither side (tokens that pass, or listed here).
const FALLBACK_UNTIL_W110 = Object.keys(THEMES);

const good = {
  id: "synthetic",
  vars: {
    [SURFACE_VAR]: "#161b22",
    "--attn-normal": "#58a6ff", "--attn-normal-ink": "#0b0f14",
    "--attn-watch": "#f2cc60", "--attn-watch-ink": "#0b0f14",
    "--attn-alert": "#ff7b72", "--attn-alert-ink": "#0b0f14",
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

describe("evaluateTheme", () => {
  it("supports a theme whose tokens meet every threshold", () => {
    const r = evaluateTheme(good);
    expect(r.failures).toEqual([]);
    expect(r.status).toBe("supported");
  });

  it("reports no-tokens, naming each missing token, when a theme has none", () => {
    const r = evaluateTheme({ id: "bare", vars: { [SURFACE_VAR]: "#161b22" } });
    expect(r.status).toBe("no-tokens");
    expect(r.missing).toContain("--attn-alert");
    expect(r.missing).toContain("--attn-watch-ink");
  });

  it("fails weak ink, a faint edge and hues that collapse under colour-vision simulation", () => {
    const ink = evaluateTheme(withVars({ "--attn-watch-ink": "#e3c060" }));
    expect(ink.status).toBe("fail");
    expect(ink.failures.join(" ")).toMatch(/watch: ink on tint/);
    const edge = evaluateTheme(withVars({ [SURFACE_VAR]: "#e3b341" }));
    expect(edge.failures.join(" ")).toMatch(/watch: edge on surface/);
    const same = evaluateTheme(withVars({ "--attn-watch": "#58a6ff" }));
    expect(same.failures.join(" ")).toMatch(/normal vs watch under (deuteranopia|protanopia|tritanopia)/);
  });

  it("states the hue threshold is provisional until W-109 fixes it", () => {
    expect(THRESHOLDS.provisional).toContain("hueDeltaE");
    expect(THRESHOLDS.textOnTint).toBe(4.5);
    expect(THRESHOLDS.edgeOnSurface).toBe(3);
  });
});

describe("the gate over the real themes", () => {
  const report = attentionReport(THEMES);

  it("covers all ten themes and a theme with tokens never fails silently", () => {
    expect(report).toHaveLength(10);
    expect(report.map((r) => r.id).sort()).toEqual(Object.keys(THEMES).sort());
    expect(report.filter((r) => r.status === "fail")).toEqual([]);
  });

  it("passes today only because every untokened theme is listed with a fallback", () => {
    expect(attentionProblems(report, FALLBACK_UNTIL_W110)).toEqual([]);
  });

  it("fails without the tokens: the same themes with no fallback list break the build", () => {
    const problems = attentionProblems(report, []);
    const untokened = report.filter((r) => r.status === "no-tokens").length;
    expect(problems).toHaveLength(untokened);
    if (untokened) expect(problems[0]).toMatch(/no attention tokens .* no listed fallback/);
  });

  it("fails a themed token set that misses a threshold, even when a fallback list exists", () => {
    const broken = [evaluateTheme(withVars({ "--attn-alert-ink": "#ff8f86" }))];
    expect(attentionProblems(broken, ["synthetic"])).toHaveLength(1);
  });

  it("produces a per-theme report for each run", () => {
    const text = formatReport(report);
    for (const id of Object.keys(THEMES)) expect(text).toContain(`| ${id} |`);
    expect(ATTN_STATES.map(attnVar)).toEqual(["--attn-normal", "--attn-watch", "--attn-alert"]);
    expect(ATTN_STATES.map(attnInkVar)[2]).toBe("--attn-alert-ink");
  });
});
