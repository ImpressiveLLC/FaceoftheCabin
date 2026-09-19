# Family Experience Journey Standard

This standard applies to Family Hub and every capability reached from it,
including FaceOfTheCabin through **How's the cabin?**. It turns periodic UX
reviews into technical work without pretending that an automated score can
replace family observation.

## Product promise

Every important flow must let a person:

1. **See** the current state in language they understand.
2. **Think** with enough context to know why it matters and what will happen.
3. **Act** without leaving the flow or rediscovering the action elsewhere.
4. **Recover** by returning to the prior context, retrying, or taking a useful
   alternative when the preferred action is unavailable.

An unavailable capability is still a designed state. Never render a blank
region, unexplained disabled control, endless spinner, or action that simply
does nothing. State what is unavailable, why when known, and provide one of:
retry, settings, a safe alternative, or relevant documentation.

## Priority journeys

### Check the schedule from a phone or tablet

- Entry: Schedule is a visible one-tap action on the Family Hub surface.
- Context: land directly on Parenting Days with today visibly identified.
- Comprehension: status labels do not depend on color alone.
- Action: scrolling never hides the close/return path.
- Recovery: if schedule data is incomplete, explain the covered date range and
  why older dates may be unknown.

### Read and send a family note

- Entry: Notes is a visible one-tap action.
- Context: recent notes and the composer share one surface.
- Attribution: if no family member is selected, ask who is writing and preserve
  the draft.
- Mobile: the composer remains above the action dock and on-screen keyboard.
- Recovery: failed server sync preserves the note locally and explains whether
  it is local-only; retry/sync status is a future enhancement, not a reason to
  hide the result.

### Ask "How's the cabin?"

- Entry: use human language, not service names or port numbers.
- Context: show last activity time, connection freshness, and privacy detail.
- Action: open the cabin experience without destroying the Family Hub context.
- Recovery: distinguish no recent activity, privacy-hidden activity, missing
  configuration, and unreachable service. Each state explains why and offers a
  next step.

## Responsive interaction rules

- High-frequency actions remain visible within the initial mobile/tablet
  viewport; secondary content may collapse.
- Collapsed sections retain descriptive headings, keyboard operation, and
  `aria-expanded` state.
- Do not nest independent vertical scroll regions unless the inner region has a
  clear boundary and the primary action remains reachable.
- Horizontal scrolling is acceptable for inherently sequential structures such
  as a calendar, but it must preserve readable target sizes and visible context.
- Fixed controls account for safe-area insets and must never cover compose,
  submit, close, retry, or explanation controls.
- Touch targets should be at least 44 CSS pixels in either dimension wherever
  the design permits.
- Focus, labels, headings, state text, and keyboard order must convey the same
  meaning as the visual presentation.

## Visual system — surfaces, tabs and themes (Cabin UI and Family Hub)

Added 2026-09-19. Until then there was no written standard, and it showed: about
20 surfaces and 128 text colors hard-coded the GitHub-dark palette
(`#161b22`, `#0d1117`, `#8b949e`, `#3fb950` ...), so in every theme except Modern
(whose palette *is* those values) sibling panels were different colors, panels
were invisible against the page (Modern: 1.24:1), and some boxes were framed
while their neighbors were plain lists. Source of truth: `surfaceLayers.js` (the
derivation), `ThemeProvider.jsx` (each theme's palette + three hue picks) and
`styles.css` "Surface layers" (the class-to-layer map).

**Principle: the app design owns structure; a theme owns only a palette and three
hue picks.** Layout, layers, anatomy, contrast and states are defined once. Adding
or changing a theme never touches a class, and no theme is allowed to patch
individual components (Asteroid City's hand-listed card overrides were exactly
that, and were removed).

### Anatomy: one pattern in every box

`panel (layer 1)  →  row or card (layer 2)  →  chip or tile (layer 3)`

| Layer | Role | Examples |
|---|---|---|
| 1 — panel | page-level box | Status Checks, Workflows, Optimization, Backend Rules, config cards, device groups, device detail, notepad, glass cards |
| 2 — row / card | a record inside a panel | workflow, rule, alert and opportunity rows; device cards; birthday, reward and profile items |
| 3 — chip / tile | a tag, count or read-out on a record | owner / mode / status chips, count badges, capability chips, KPI tiles |

**List or tile?** Records that share a shape and carry text plus actions
(workflows, rules, alerts, opportunities) are **list rows** — one row anatomy: dot,
name, detail line, chips, action buttons — because rows scan, sort and compare, and
tiles waste space on long text in narrow columns. Tiles are for **glanceable
values** (KPI read-outs, camera health). Do not mix framed and frameless records in
one page: a record is always a framed layer-2 row, and its tags are always layer-3
chips. (Optimization Opportunities used to render framed cards while its sibling
boxes rendered plain rows; it now uses the shared row.)

### Palette spec — what a theme can and can't do

Enforced over every theme by `SurfaceLayers.test.jsx` (Cabin UI) and
`SurfaceLayersDrift.test.jsx` (Family Hub's palettes, and that both apps carry the
same picks). The derivation auto-strengthens edges, so most palettes pass by
construction; a pick that can't pass fails the build.

| Rule | Requirement |
|---|---|
| **CAN border** | A layer's edge is ≥ **3:1** (WCAG 1.4.11) against every surface it can sit on: L1 vs the page; L2 vs L1's fill *and* the page; L3 vs L2's fill, L1's fill and the page |
| **CAN'T overlap** | Adjacent layers never share a hue (no pink-on-pink): hues differ by ≥ 30°, or by ≥ 1.6:1 luminance for neutral hues |
| **CAN'T recolor text** | The theme's `--text` reads ≥ **4.5:1** on every fill; fills are tints that back off until it does. Layers never set `color` |
| **CAN'T use danger** | A layer hue may not be the theme's danger color |
| **CAN'T go unstyled** | Every theme sets every layer variable, so switching themes can never leave a previous theme's value behind |
| **CAN'T hard-code** | No component rule may contain a hex color (allowed: `#000` behind video and the category badge) — a guard test fails the build |

If a theme looks flat, **swap which palette color plays which layer** (edit that
theme's `layers.hues`), don't restyle a class. Current picks and measured results
(worst-case edge contrast per layer; text = worst fill):

| Theme | L1 (panel) | L2 (card) | L3 (chip) | edge L1/page | edge L2 | edge L3 | text |
|---|---|---|---|---:|---:|---:|---:|
| modern | `#388bfd` | `#e6edf3` | `#3fb950` | 3.2 | 5.9 | 3.2 | 13.1 |
| impressive | `#3d3dff` | `#989d9e` | `#eff0ec` | 3.6 | 3.4 | 6.9 | 14.7 |
| lcars | `#cc6600` | `#ff9900` | `#99cc00` | 5.5 | 7.0 | 7.1 | 8.6 |
| monolith | `#666666` | `#cccccc` | `#448844` | 3.1 | 5.4 | 3.1 | 10.9 |
| retrocrt | `#00cc33` | `#ffcc00` | `#00ff41` | 5.5 | 6.0 | 6.6 | 12.5 |
| bluefin | `#4080c0` | `#a0c8f0` | `#40a080` | 3.0 | 4.7 | 3.3 | 9.2 |
| madscience | `#39ff14` | `#bf00ff` | `#f5f500` | 7.1 | 3.0 | 6.6 | 14.8 |
| deepspace | `#00a3ff` | `#ff9500` | `#ffffff` | 3.9 | 3.8 | 7.2 | 16.2 |
| **neon80s** | `#ff2dd4` pink | `#add8e6` blue | `#ffff66` yellow | 3.8 | 5.5 | 7.3 | 15.1 |
| pacman | `#ffff00` | `#00ffde` | `#ffaa00` | 8.7 | 6.2 | 4.3 | 16.4 |
| asteroidcity | `#e34e24` | `#608c4a` | `#ffffff` | 3.9 | 3.9 | 3.9 | 5.2 |

80s Neon is pink (main) → pale blue (frames and rows) → yellow (chips and tabs),
with a limited glow per layer; Asteroid City uses hard postcard shadows and
charcoal edges instead. The derived fill, edge, shadow and border width for each
layer are `--layer-N-fill / -edge / -shadow / -border-width`; `--layer-1..3` are
the hues themselves.

### Tabs, notification dots and status colors

- **Tab buttons** (left rail, dashboard and view tabs) are one color per theme
  (`--tab-color`) in every state. A warn notification is a dot badge only — the icon
  is never tinted; critical keeps its red bar, label and pulse; the active tab is
  marked by its outline / underline, never a different text color. 80s Neon: every
  tab is `#ffff66`; its warn dot is pink so it stays visible on a yellow tab.
- **Notification dots follow the banner.** The rail, the attention banner and the
  Status Checks list all derive from `mergeStatusCheckItems()` (`navAlertLevelsFor()`),
  so ignored or snoozed alerts, other locations and automation alerts count the same
  everywhere.
- **Structural vs status colors.** In 80s Neon yellow and aquamarine are structural
  (layers, tabs); status is carried by icon, wording and badge shape, and danger
  stays pink-red. `--link` and `--on-accent` are derived, so text on an accent fill
  is black or white, whichever reads (white on Pac-Man's yellow accent did not).

### Adding things

- **A component:** add its class to the matching `:is()` list in `styles.css`
  (Cabin UI) or the layer block in `family-hub.html`. Never hard-code a surface color.
- **A theme:** supply its palette and `layers: { hues: [...] }` (plus `shadow`,
  `widths`, `edgeHue`, `tints`, `tab` only if it needs them), in **both**
  `ThemeProvider.jsx` and `family-hub.html`; the tests tell you if a pick fails.
- **Family Hub** flattens its translucent glass palette over the page and runs the
  same function (embedded byte-for-byte, drift-tested); its fills keep the glass
  see-through so the scene shows behind a card.

## Automated evidence

The Family Hub browser suite must cover phone and desktop viewports and verify:

- Schedule and Notes are directly reachable.
- The selected action lands in the intended context.
- No document-level horizontal overflow exists on phone.
- The note composer does not overlap the mobile action dock.
- Secondary overview cards collapse while the next schedule remains expanded.
- Unavailable or disabled capabilities contain visible explanatory text.
- JavaScript errors remain zero for the covered journeys.

Visual, accessibility, performance, and live-service checks should be added as
separate evidence layers. A live environment failure must be reported as an
environment or service state, not misrepresented as a product assertion failure.

## Periodic review output

For each new use case, proof of concept, or drift review, capture:

1. Person, device, goal, and starting context.
2. See / Think / Act / Recover observations at every step.
3. Friction, ambiguity, dead ends, inaccessible behavior, and privacy impact.
4. Desired experience independent of current implementation limits.
5. Technical decomposition: UI, API/event state, data, configuration, tests,
   deployment, and documentation.
6. Before/after screenshots and regression tests for accepted improvements.
