# W-109 draft: attention presentation matrix

Status: **draft for Nate review (Cowork, 2026-10-25).** Completes the framework [section 15.3](2026-10-22-measurement-attention-framework.md#153-channels-per-state-starting-proposal-for-w-109-to-complete-not-decided) matrix under the rulings in section 15.4: MA-16 (no pulsing, no user toggles, always-on non-color cues), MA-17 (per browser, deferred to W-116), MA-18 (override only where a theme supports it; other themes get their own striking cue). Mockups per theme and width are still owed before W-109 can close. No code.

## 1. Rules that apply to every state and theme

1. **No pulsing, blinking or looping animation** on any attention state. The existing critical alert keeps its own red bar, label and pulse, untouched, and remains reserved to it.
2. **Hue is never the only difference.** Every state carries a text label, an icon silhouette, a border pattern and, for Watch and Alert, a corner cue (below). The corner cue and the pattern are always on, not an option.
3. **Corner cue vocabulary** (Nate, MA-16: an asterisk at the corners or a dotted border for each color-blind-relevant state): Watch uses a **dotted** medium border; Alert uses a thick solid border with an **asterisk in all four corners**. The two never share a cue, so a person who cannot tell the hues apart can still tell Watch from Alert.
4. **Distinct from the critical alert.** The critical alert is a full-width red bar plus label. Attention states never use a bar. Alert uses corner asterisks, which the critical alert does not.
5. **Status has its own tokens** (`--attn-normal`, `--attn-watch`, `--attn-alert`, plus `-ink` for text on them), separate from `--layer-N-*` and from `--danger`, so the UX Journey layer rule and the device-state `.state-alarm` border are not disturbed (conflicts 1 and 2 of section 15.2).
6. **Both surfaces.** Tokens are defined in `ThemeProvider.jsx` and in `family-hub.html`, and the existing theme drift tests extend to them (conflict 3).
7. **System settings are followed automatically:** `forced-colors: active` drops tints and relies on border pattern, icon and text; `prefers-contrast: more` thickens borders; `prefers-reduced-motion` has nothing to remove for these states.

## 2. State matrix

| State | Label (always shown) | Icon silhouette | Border | Corner cue | Hue token | Motion |
|---|---|---|---|---|---|---|
| Normal | Normal | circle with check | thin, solid | none | `--attn-normal` | none |
| Watch | Watch | triangle | medium, **dotted** | none (dotted border is the cue) | `--attn-watch` | none |
| Alert | Alert | octagon with exclamation | thick, solid, inset edge | **asterisk in all four corners** | `--attn-alert` | none |
| Unclassified | Unavailable | dashed circle | thin, dashed, no tint | none | neutral | none |
| Last known Alert | Last known Alert; current unavailable | Alert octagon outlined, with clock | dashed, in the Alert hue, no fill | corner asterisks drawn hollow | `--attn-alert`, outline only | none |
| Partial coverage | Partial coverage (badge) | half-filled badge added to the assessed state | as the assessed state | as the assessed state | as assessed | none |
| Latched alarm | per existing alarm | per existing alarm | existing critical treatment | none (existing red bar) | existing | existing, unchanged |

## 3. Themes (10) and the unsupported-theme rule

Each theme either supplies a status triplet that meets the section 15.5 thresholds with its own hues (supported), or it does not (unsupported). An unsupported theme keeps its look but its status layer falls back to a compliant palette, and the always-on cues in section 2 are drawn at their strongest weight there so attention is obvious without hue. W-111 produces the per-theme report; the column below is a **starting hypothesis to verify, not a finding**.

| Theme | Hypothesis | Supplement if unsupported |
|---|---|---|
| Modern | **fails the deuteranopia check (Codex, W-111, 2026-10-26): its own green, amber and red are not distinguishable** | do not reuse its status hues as attention hues; compliant status layer; cues at strongest weight |
| Impressive | likely supported | none |
| LCARS | structural orange and violet may collide with Watch | compliant status layer; heavier dotted and asterisk weight |
| Monolith | mono palette: hue weak by design | rely on pattern, icon, label; add inset edge to Watch |
| Bluefin-mono | mono palette: hue weak by design | same as Monolith |
| Mad Science | structural green may collide with Normal | compliant status layer |
| Deep Space | likely supported, check Alert on dark ground | verify contrast |
| 80s Neon | structural yellow, aquamarine and pink collide; status already carried by icon, wording and badge shape | compliant status layer; cues at strongest weight |
| Pac-Man | structural yellow may collide with Watch | compliant status layer |
| Asteroid City | pastel palette: low contrast risk on Normal and Watch | compliant status layer; thicker borders |

## 4. Open items before W-109 closes

- Mockups per theme at kiosk, tablet and phone widths, reviewed by Nate.
- Exact `--attn-*` values per theme and measured contrast, produced with W-111 (failing-without-tokens proof).
- Asterisk glyph size and corner inset, one value per surface so Cabin UI and Family Hub match.
- Confirm no attention state reuses the critical alert's red bar.

## 5. Cowork rulings for the W-111 checks (2026-10-26)

Asked by Code on #244 after #266. These set the method; the numbers are proposals Nate may amend. None blocks a release by itself: the always-on non-color cues in section 2 carry meaning, so a failed hue check marks a theme **unsupported** (section 3 fallback), it does not fail the theme.

1. **Hue distinguishability.** Use CIEDE2000, not CIE76 (CIE76 over-weights saturated hues and is not perceptually even). Rule: each pair of Normal, Watch and Alert must reach delta-E00 of at least 10 under simulated protan, deutan and tritan vision (Vienot or Machado simulation, severity 1.0). The provisional CIE76 value of 15 stays in `THRESHOLDS` only until CIEDE2000 lands in W-110, and the report labels it provisional until then. A pair below threshold marks that theme unsupported.
2. **Edge surface.** A border sits on the card face and against the page behind it. Check non-text contrast (3:1, the WCAG 1.4.11 level) against **both** `--bg` and `--bg-secondary` and use the worse result, per theme. Using `--bg-secondary` alone is not enough.
3. **Family Hub.** In scope and required, not optional. `family-hub.html` gets the same `--attn-*` tokens and the drift tests extend to it (section 1, rule 6). The W-111 report lists the Family Hub surface separately per theme. It stays on the PENDING list until W-110 adds the tokens.
4. Automated results never replace the W-105 human gate or Nate's mockup review.

5a. **Lightness rule (Codex finding on Modern, ruled by Cowork 2026-10-26; Nate may amend).** Watch and Alert must differ in lightness as well as hue: at least 20 points of L* between them (and between Normal and Watch), measured after protan, deutan and tritan simulation. A green, amber, red triplet of similar lightness fails this by design. Themes whose own status hues fail either this or the delta-E00 rule in section 5 are unsupported and use the compliant status layer. W-110 must not reuse Modern's own status hues as attention hues. The 20-point value is provisional until W-111 measures real themes.
