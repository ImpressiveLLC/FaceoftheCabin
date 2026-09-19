// <surface-layers>
// One place that decides what a panel / card / tile looks like in ANY theme.
// A theme supplies its palette plus three hues (largest object -> smallest);
// this derives fills, edges, shadows and widths and guarantees separation, so
// a low-visibility palette fixes itself and a new theme needs no per-class
// tuning. The same function is embedded, byte-for-byte between these markers,
// in family-hub/family-hub.html (SurfaceLayersDrift.test.jsx fails the build if
// the two copies differ). Spec and rationale: docs/UX_JOURNEY_STANDARD.md
// "Visual system".
//
//   palette = { page, panel, border, text, muted, accent } (all #rrggbb)
//   opts    = { hues:[h1,h2,h3], shadow:'glow'|'hard'|'none',
//               widths:[w1,w2,w3], edgeHue: 0..1 (share of the hue in edges),
//               tints:[t1,t2,t3] (share of the hue in each fill), tab: '#rrggbb' }
//
// CAN / CAN'T (enforced by the tests over every theme):
//   - a layer's edge is >= EDGE_CONTRAST against the surface it sits on
//     (L1 vs the page, L2 vs L1's fill, L3 vs L2's and L1's fill);
//   - adjacent layers never share a hue (no pink-on-pink): hues differ by >=
//     30deg, or by >= 1.6:1 luminance for neutral hues;
//   - --text reads >= TEXT_CONTRAST on every fill and layers never recolor text;
//   - a layer hue may not be the theme's danger color.
const EDGE_CONTRAST = 3.0;
const TEXT_CONTRAST = 4.5;

const toRgb = (hex) => { const x = hex.replace('#', ''); return [0, 2, 4].map(i => parseInt(x.slice(i, i + 2), 16)); };
const toHex = (rgb) => '#' + rgb.map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
const mix = (a, b, share) => a.map((v, i) => v * share + b[i] * (1 - share)); // share = amount of a
const channel = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
const luminance = (rgb) => 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
export function contrastRatio(a, b) {
  const [hi, lo] = [luminance(toRgb(a)), luminance(toRgb(b))].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
export function hueAngle(hex) {
  const [r, g, b] = toRgb(hex).map(v => v / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (d === 0) return { h: 0, s: 0 };
  let h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  h = (h * 60 + 360) % 360;
  const l = (max + min) / 2;
  return { h, s: d / (1 - Math.abs(2 * l - 1)) };
}
export function huesAreDistinct(a, b) {
  const A = hueAngle(a), B = hueAngle(b);
  const diff = Math.min(Math.abs(A.h - B.h), 360 - Math.abs(A.h - B.h));
  return (A.s > 0.15 && B.s > 0.15 && diff >= 30) || contrastRatio(a, b) >= 1.6;
}

// Fill: a tint of the hue over its base, backed off until text still reads.
function fitFill(hue, base, tint, text) {
  let t = tint, fill = mix(hue, base, t);
  while (t > 0 && contrastRatio(toHex(fill), text) < TEXT_CONTRAST) { t = Math.max(0, t - 0.02); fill = mix(hue, base, t); }
  return toHex(fill);
}
// Edge: the hue (blended toward the border for a softer line), strengthened
// toward the hue itself and then toward the text color until it separates.
function fitEdge(hue, border, text, against, hueShare) {
  const ok = (c) => against.every(s => contrastRatio(toHex(c), s) >= EDGE_CONTRAST);
  let edge = mix(hue, border, hueShare);
  if (ok(edge)) return toHex(edge);
  for (let s = hueShare; s <= 1.001; s += 0.08) { edge = mix(hue, border, Math.min(1, s)); if (ok(edge)) return toHex(edge); }
  for (let u = 0.1; u <= 1.001; u += 0.1) { edge = mix(text, hue, u); if (ok(edge)) return toHex(edge); }
  return toHex(edge);
}
const withAlpha = (hex, a) => { const [r, g, b] = toRgb(hex); return `rgba(${r}, ${g}, ${b}, ${a})`; };

export function deriveSurfaceLayers(palette, opts) {
  const { page, panel, border, text, muted } = palette;
  const hues = opts.hues.map(toRgb);
  const light = luminance(toRgb(panel)) > 0.4;
  const tint = opts.tints || (light ? [0.32, 0.26, 0.2] : [0.09, 0.06, 0.08]);
  const hueShare = opts.edgeHue ?? 0.62;
  const P = toRgb(page), B = toRgb(border), T = toRgb(text);

  const fill1 = fitFill(hues[0], toRgb(panel), tint[0], text);
  const fill2 = fitFill(hues[1], light ? toRgb(fill1) : P, tint[1], text);
  const fill3 = fitFill(hues[2], light ? toRgb(fill2) : P, tint[2], text);
  const edge1 = fitEdge(hues[0], B, T, [page], hueShare);
  const edge2 = fitEdge(hues[1], B, T, [fill1], hueShare);
  const edge3 = fitEdge(hues[2], B, T, [fill2, fill1], hueShare);

  const shape = opts.shadow || 'none';
  const shadows = shape === 'glow'
    ? [`0 0 5px ${withAlpha(edge1, 0.42)}, 0 0 16px ${withAlpha(edge1, 0.15)}`,
       `0 0 4px ${withAlpha(edge2, 0.32)}, 0 0 11px ${withAlpha(edge2, 0.09)}`,
       `0 0 4px ${withAlpha(edge3, 0.36)}`]
    : shape === 'hard'
      ? [`6px 6px 0 ${border}`, `4px 4px 0 ${border}`, `3px 3px 0 ${border}`]
      : ['none', 'none', 'none'];

  return {
    fills: [fill1, fill2, fill3],
    edges: [edge1, edge2, edge3],
    shadows,
    widths: opts.widths || ['1px', '1px', '1px'],
    hues: opts.hues,
    hoverEdge: opts.hues[1],
    selectedEdge: opts.hues[0],
    tab: opts.tab || muted,
    tabHover: text,
  };
}

// The CSS custom properties the stylesheet reads. Every theme sets ALL of them,
// so switching themes can never leave a previous theme's value behind.
export function surfaceLayerVars(palette, opts) {
  const d = deriveSurfaceLayers(palette, opts);
  const v = {};
  [1, 2, 3].forEach((n, i) => {
    v[`--layer-${n}`] = d.hues[i];
    v[`--layer-${n}-fill`] = d.fills[i];
    v[`--layer-${n}-edge`] = d.edges[i];
    v[`--layer-${n}-shadow`] = d.shadows[i];
    v[`--layer-${n}-border-width`] = d.widths[i];
  });
  v['--layer-hover-edge'] = d.hoverEdge;
  v['--layer-hover-shadow'] = d.shadows[1];
  v['--layer-selected-edge'] = d.selectedEdge;
  v['--layer-selected-shadow'] = d.shadows[0];
  v['--tab-color'] = d.tab;
  v['--tab-color-hover'] = d.tabHover;
  // Text on an accent fill: whichever of white/black reads better on the accent.
  v['--on-accent'] = contrastRatio(palette.accent, '#ffffff') >= contrastRatio(palette.accent, '#000000') ? '#ffffff' : '#000000';
  return v;
}
// </surface-layers>
