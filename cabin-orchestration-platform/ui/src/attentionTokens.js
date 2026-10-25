// W-111 scaffold: verify the attention status tokens (Normal / Watch / Alert) of every theme.
//
// Spec: docs/governance/backlog.md W-111, framework section 15.5, matrix docs/governance/proposals/2026-10-26-w109-attention-presentation-matrix.md.
// A theme either supplies the status triplet and meets the thresholds below (supported), supplies it and misses a
// threshold (fail), or supplies nothing yet (no-tokens: its status layer falls back to a compliant palette, W-110).
// Automated results do not replace the W-105 human gate.
import { contrastRatio } from "./surfaceLayers.js";

export const ATTN_STATES = ["normal", "watch", "alert"];
export const attnVar = (state) => `--attn-${state}`;
export const attnInkVar = (state) => `--attn-${state}-ink`;
// Surface the border and icon sit on. The theme's panel colour, which the cards use.
export const SURFACE_VAR = "--bg-secondary";

export const THRESHOLDS = {
  textOnTint: 4.5, // ink on its own tint, WCAG AA text
  edgeOnSurface: 3, // border and icon against the adjacent surface, WCAG non-text
  // Pairwise distinguishability of the three hues under simulated colour-vision deficiency, as CIE76 deltaE.
  // PROVISIONAL: W-109 fixes the method and threshold; 15 is a placeholder to be replaced there, not a decision.
  hueDeltaE: 15,
  provisional: ["hueDeltaE"],
};

export const DEFICIENCIES = ["deuteranopia", "protanopia", "tritanopia"];

// Machado, Oliveira and Fernandes (2009), severity 1.0, applied in linear sRGB.
const MATRICES = {
  protanopia: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
  deuteranopia: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
  tritanopia: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.303900]],
};

const toRgb = (hex) => {
  const h = String(hex).trim().replace(/^#/, "");
  const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
  if (!/^[0-9a-f]{6}$/i.test(full)) throw new Error(`not a #rrggbb colour: ${hex}`);
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
};
const toLinear = (v) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
const fromLinear = (v) => { const c = Math.min(1, Math.max(0, v)); return 255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055); };

export function simulate(hex, deficiency) {
  const m = MATRICES[deficiency];
  if (!m) throw new Error(`unknown deficiency: ${deficiency}`);
  const lin = toRgb(hex).map(toLinear);
  return m.map((row) => fromLinear(row[0] * lin[0] + row[1] * lin[1] + row[2] * lin[2]));
}

function toLab(rgb) {
  const [r, g, b] = rgb.map(toLinear);
  const x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
  const y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  const z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
  const f = (t) => (t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116);
  const [fx, fy, fz] = [f(x), f(y), f(z)];
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}
export const deltaE = (rgbA, rgbB) => Math.hypot(...toLab(rgbA).map((v, i) => v - toLab(rgbB)[i]));

export function evaluateTheme(theme) {
  const vars = theme.vars || {};
  const needed = [...ATTN_STATES.flatMap((s) => [attnVar(s), attnInkVar(s)]), SURFACE_VAR];
  const missing = needed.filter((v) => !vars[v]);
  if (missing.length) return { id: theme.id, status: "no-tokens", missing, failures: [] };
  const failures = [];
  for (const state of ATTN_STATES) {
    const tint = vars[attnVar(state)];
    const ink = contrastRatio(vars[attnInkVar(state)], tint);
    if (ink < THRESHOLDS.textOnTint) failures.push(`${state}: ink on tint ${ink.toFixed(2)}:1 < ${THRESHOLDS.textOnTint}:1`);
    const edge = contrastRatio(tint, vars[SURFACE_VAR]);
    if (edge < THRESHOLDS.edgeOnSurface) failures.push(`${state}: edge on surface ${edge.toFixed(2)}:1 < ${THRESHOLDS.edgeOnSurface}:1`);
  }
  for (const deficiency of DEFICIENCIES) {
    for (let i = 0; i < ATTN_STATES.length; i++) {
      for (let j = i + 1; j < ATTN_STATES.length; j++) {
        const d = deltaE(simulate(vars[attnVar(ATTN_STATES[i])], deficiency), simulate(vars[attnVar(ATTN_STATES[j])], deficiency));
        if (d < THRESHOLDS.hueDeltaE) failures.push(`${ATTN_STATES[i]} vs ${ATTN_STATES[j]} under ${deficiency}: deltaE ${d.toFixed(1)} < ${THRESHOLDS.hueDeltaE} (provisional)`);
      }
    }
  }
  return { id: theme.id, status: failures.length ? "fail" : "supported", missing: [], failures };
}

export const attentionReport = (themes) => Object.values(themes).map(evaluateTheme);

// Strict gate. A theme without tokens must be named in `allowedFallback` (its status layer uses the compliant fallback);
// a theme with tokens must pass. Returns the problems, empty when the build may pass.
export function attentionProblems(report, allowedFallback = []) {
  const problems = [];
  for (const r of report) {
    if (r.status === "fail") problems.push(`${r.id}: ${r.failures.join("; ")}`);
    if (r.status === "no-tokens" && !allowedFallback.includes(r.id)) problems.push(`${r.id}: no attention tokens (${r.missing.join(", ")}) and no listed fallback`);
  }
  return problems;
}

export function formatReport(report) {
  return ["| theme | status | detail |", "|---|---|---|",
    ...report.map((r) => `| ${r.id} | ${r.status} | ${r.status === "no-tokens" ? "missing " + r.missing.length + " tokens" : r.failures.join("; ") || "all thresholds met"} |`)].join("\n");
}
