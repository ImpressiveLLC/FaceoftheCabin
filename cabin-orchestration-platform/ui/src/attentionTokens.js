// W-111 scaffold: verify the attention status tokens (Normal / Watch / Alert) of every theme.
//
// Spec: docs/governance/backlog.md W-111, framework section 15.5, matrix docs/governance/proposals/2026-10-26-w109-attention-presentation-matrix.md.
// A theme is `supported` (full triplet, provisional thresholds met), `unsupported` (complete and legible, but a hue pair is too close
// under colour-vision simulation: ruled a status-layer fallback, NOT a build failure), `fail` (supplied tokens miss a contrast threshold), `partial`
// (some but not all tokens: always a failure, never covered by an exception) or `no-tokens` (nothing supplied yet: pending
// W-110; no fallback palette exists in these files). `supported` means "meets the provisional numbers", not human-verified.
// Rulings (Cowork, W-109 matrix section 5, 2026-10-26): hue distinctness is CIEDE2000, at least 10 per pair under protan, deutan and
// tritan simulation (the numbers are proposals Nate may amend); the edge is checked against BOTH `--bg` and `--bg-secondary` and the worse
// result counts; Family Hub is in scope and reported as its own surface. A failed hue check marks a theme unsupported, it does not block a release.
// Not covered here, so NOT_RUN: the other W-109 states, dotted/asterisk cues, forced colours, the W-105 human gate.
import { contrastRatio } from "./surfaceLayers.js";

export const ATTN_STATES = ["normal", "watch", "alert"];
export const attnVar = (state) => `--attn-${state}`;
export const attnInkVar = (state) => `--attn-${state}-ink`;
// Surfaces the border and icon sit on, per surface: the card face and the page behind it. The worse contrast counts.
// Family Hub draws cards as translucent glass over `--night`, so `--night` is its only opaque surface.
export const SURFACES = { cabin: ["--bg", "--bg-secondary"], hub: ["--night"] };

export const THRESHOLDS = {
  textOnTint: 4.5, // ink on its own tint, WCAG AA text
  edgeOnSurface: 3, // border and icon against the adjacent surface, WCAG non-text
  // Pairwise distinguishability of the three hues under simulated colour-vision deficiency, as CIEDE2000.
  // A proposal Nate may amend (W-109 matrix section 5), so still reported as provisional.
  hueDeltaE00: 10,
  provisional: ["hueDeltaE00"],
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

// CIEDE2000 (Sharma, Wu and Dalal 2005), kL = kC = kH = 1.
export function deltaE00FromLab([L1, a1, b1], [L2, a2, b2]) {
  const rad = (d) => (d * Math.PI) / 180;
  const deg = (r) => (r * 180) / Math.PI;
  const C1 = Math.hypot(a1, b1), C2 = Math.hypot(a2, b2);
  const Cbar = (C1 + C2) / 2;
  const G = 0.5 * (1 - Math.sqrt(Cbar ** 7 / (Cbar ** 7 + 25 ** 7)));
  const a1p = (1 + G) * a1, a2p = (1 + G) * a2;
  const C1p = Math.hypot(a1p, b1), C2p = Math.hypot(a2p, b2);
  const hp = (b, ap) => (b === 0 && ap === 0 ? 0 : (deg(Math.atan2(b, ap)) + 360) % 360);
  const h1p = hp(b1, a1p), h2p = hp(b2, a2p);
  const dLp = L2 - L1, dCp = C2p - C1p;
  let dhp = 0;
  if (C1p * C2p !== 0) {
    dhp = h2p - h1p;
    if (dhp > 180) dhp -= 360;
    else if (dhp < -180) dhp += 360;
  }
  const dHp = 2 * Math.sqrt(C1p * C2p) * Math.sin(rad(dhp / 2));
  const Lbarp = (L1 + L2) / 2, Cbarp = (C1p + C2p) / 2;
  let hbarp = h1p + h2p;
  if (C1p * C2p !== 0) {
    if (Math.abs(h1p - h2p) <= 180) hbarp /= 2;
    else hbarp = (h1p + h2p < 360 ? hbarp + 360 : hbarp - 360) / 2;
  }
  const T = 1 - 0.17 * Math.cos(rad(hbarp - 30)) + 0.24 * Math.cos(rad(2 * hbarp)) + 0.32 * Math.cos(rad(3 * hbarp + 6)) - 0.2 * Math.cos(rad(4 * hbarp - 63));
  const dTheta = 30 * Math.exp(-(((hbarp - 275) / 25) ** 2));
  const Rc = 2 * Math.sqrt(Cbarp ** 7 / (Cbarp ** 7 + 25 ** 7));
  const Sl = 1 + (0.015 * (Lbarp - 50) ** 2) / Math.sqrt(20 + (Lbarp - 50) ** 2);
  const Sc = 1 + 0.045 * Cbarp, Sh = 1 + 0.015 * Cbarp * T;
  const Rt = -Math.sin(rad(2 * dTheta)) * Rc;
  return Math.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh));
}
export const deltaE00 = (rgbA, rgbB) => deltaE00FromLab(toLab(rgbA), toLab(rgbB));

export function evaluateTheme(theme, surface = "cabin") {
  const vars = theme.vars || {};
  const surfaceVars = SURFACES[surface];
  if (!surfaceVars) throw new Error(`unknown surface: ${surface}`);
  const attnNeeded = ATTN_STATES.flatMap((s) => [attnVar(s), attnInkVar(s)]);
  const needed = [...attnNeeded, ...surfaceVars];
  const missing = needed.filter((v) => !vars[v]);
  const suppliedAttn = attnNeeded.filter((v) => vars[v]);
  if (missing.length && !suppliedAttn.length) return { id: theme.id, surface, status: "no-tokens", missing, failures: [], hueFailures: [] };
  const failures = [];
  const hueFailures = [];
  if (missing.length) failures.push(`incomplete token set, missing ${missing.join(", ")}`);
  const have = (...names) => names.every((n) => vars[n]);
  for (const state of ATTN_STATES) {
    const tint = vars[attnVar(state)];
    if (!have(attnVar(state), attnInkVar(state))) continue;
    const ink = contrastRatio(vars[attnInkVar(state)], tint);
    if (ink < THRESHOLDS.textOnTint) failures.push(`${state}: ink on tint ${ink.toFixed(2)}:1 < ${THRESHOLDS.textOnTint}:1`);
    const edge = Math.min(...surfaceVars.filter((v) => vars[v]).map((v) => contrastRatio(tint, vars[v])), Infinity);
    if (edge < THRESHOLDS.edgeOnSurface) failures.push(`${state}: edge on surface ${edge.toFixed(2)}:1 < ${THRESHOLDS.edgeOnSurface}:1`);
  }
  for (const deficiency of DEFICIENCIES) {
    for (let i = 0; i < ATTN_STATES.length; i++) {
      for (let j = i + 1; j < ATTN_STATES.length; j++) {
        if (!have(attnVar(ATTN_STATES[i]), attnVar(ATTN_STATES[j]))) continue;
        const d = deltaE00(simulate(vars[attnVar(ATTN_STATES[i])], deficiency), simulate(vars[attnVar(ATTN_STATES[j])], deficiency));
        if (d < THRESHOLDS.hueDeltaE00) hueFailures.push(`${ATTN_STATES[i]} vs ${ATTN_STATES[j]} under ${deficiency}: deltaE00 ${d.toFixed(1)} < ${THRESHOLDS.hueDeltaE00} (provisional)`);
      }
    }
  }
  const status = missing.length ? "partial" : failures.length ? "fail" : hueFailures.length ? "unsupported" : "supported";
  return { id: theme.id, surface, status, missing, failures, hueFailures };
}

export const attentionReport = (themes, surface = "cabin") => Object.values(themes).map((t) => evaluateTheme(t, surface));

// Family Hub keeps its themes in an inline `const THEMES = {...}` in family-hub.html. Pull each theme's id and its quoted CSS variables.
export function familyHubThemes(html) {
  const start = html.indexOf("const THEMES = {");
  if (start === -1) throw new Error("could not find `const THEMES = {` in family-hub.html");
  const block = html.slice(start, html.indexOf("\n};", start));
  const themes = {};
  for (const chunk of block.split(/\n  (?=[a-z0-9]+: \{)/).slice(1)) {
    const id = /id:\s*'([^']+)'/.exec(chunk)?.[1];
    if (!id) continue;
    const vars = {};
    for (const m of chunk.matchAll(/'(--[a-z0-9-]+)'\s*:\s*'([^']*)'/g)) vars[m[1]] = m[2];
    themes[id] = { id, vars };
  }
  return themes;
}

// Strict gate. A theme with no tokens at all must be named in `allowedPending` (an explicit, reasoned list, not derived from the
// themes); a theme with any tokens must have a complete set that meets the contrast thresholds, and a partial or contrast-failing set is
// a problem even when the theme is listed. A hue-only miss (`unsupported`) is not a build problem: per the W-109 ruling it routes the theme
// to the fallback status layer, and the always-on non-colour cues carry meaning. Returns the problems, empty when the build may pass.
export function attentionProblems(report, allowedPending = []) {
  const problems = [];
  for (const r of report) {
    if (r.status === "fail" || r.status === "partial") problems.push(`${r.id}: ${r.failures.join("; ")}`);
    if (r.status === "no-tokens" && !allowedPending.includes(r.id)) problems.push(`${r.id}: no attention tokens (${r.missing.join(", ")}) and not on the pending list`);
  }
  return problems;
}

export function formatReport(report) {
  const detail = (r) => (r.status === "no-tokens" ? "pending W-110: no attention tokens, nothing implemented"
    : [...r.failures, ...(r.hueFailures || [])].join("; ") || "meets the provisional thresholds (not human-verified)");
  return ["| theme | surface | status | detail |", "|---|---|---|---|", ...report.map((r) => `| ${r.id} | ${r.surface} | ${r.status} | ${detail(r)} |`), "",
    "Covers Normal/Watch/Alert tokens only. NOT_RUN: other W-109 states, dotted and asterisk cues, forced colours, W-105 human gate."].join("\n");
}
