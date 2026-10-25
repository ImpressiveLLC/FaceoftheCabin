import { describe, it, expect } from "vitest";
import { THEMES } from "./ThemeProvider.jsx";
import {
  ATTN_STATES, attnVar, attnInkVar, SURFACE_VAR, THRESHOLDS, DEFICIENCIES,
  simulate, deltaE, evaluateTheme, attentionReport, attentionProblems, formatReport,
} from "./attentionTokens.js";

// Themes with no --attn-* tokens yet: pending W-110, nothing implemented (no fallback palette exists in the code).
// An explicit list, written out on purpose and NOT derived from THEMES, so a theme added later fails the gate until someone
// either gives it tokens or adds it here with a reason. Remove an id as its tokens land. Expiry: empty when W-110 closes.
const PENDING_W110 = [
  "modern", "impressive", "lcars", "monolith", "bluefin", "madscience", "deepspace", "neon80s", "pacman", "asteroidcity",
];

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
    expect(report.filter((r) => r.status === "fail" || r.status === "partial")).toEqual([]);
  });

  it("keeps the pending list honest: every entry is a real theme, and the list is independent of THEMES", () => {
    for (const id of PENDING_W110) expect(Object.keys(THEMES)).toContain(id);
    expect(new Set(PENDING_W110).size).toBe(PENDING_W110.length);
  });

  it("passes today only because every untokened theme is named on the pending list", () => {
    expect(attentionProblems(report, PENDING_W110)).toEqual([]);
  });

  it("fails without the tokens: the same themes with an empty pending list break the build", () => {
    const problems = attentionProblems(report, []);
    const untokened = report.filter((r) => r.status === "no-tokens").length;
    expect(problems).toHaveLength(untokened);
    if (untokened) expect(problems[0]).toMatch(/no attention tokens .* not on the pending list/);
  });

  it("a theme added later and not on the pending list fails the gate", () => {
    const withNew = attentionReport({ ...THEMES, brandnew: { id: "brandnew", vars: { "--bg-secondary": "#101010" } } });
    expect(attentionProblems(withNew, PENDING_W110)).toHaveLength(1);
    expect(attentionProblems(withNew, PENDING_W110)[0]).toMatch(/^brandnew:/);
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

  it("reports pending themes as unimplemented, not as having a compliant fallback", () => {
    const text = formatReport(report);
    expect(text).toMatch(/pending W-110: no attention tokens, nothing implemented/);
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
