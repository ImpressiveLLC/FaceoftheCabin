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

## Visual system — surfaces, tabs and themes (Cabin UI)

Added 2026-09-19. Until then there was no written standard, and it showed:
about 20 surfaces hard-coded GitHub-dark hex (`#161b22`, `#0d1117`, `#010409`)
that ignore every theme, so sibling panels on one page were different colors in
every preset except Modern (whose palette happens to be those values). The
source of truth is `styles.css` ("Surface layers"); this section is the rule.

**Every boxed surface belongs to exactly one layer, largest to smallest:**

| Layer | Role | Examples |
|---|---|---|
| 1 — panel | page-level panel or card | Status Checks, Workflows, Backend Rules, config cards, device groups, device detail, sensor history, modals, event log |
| 2 — card | mid-size object nested in a panel | device cards/rows, opportunity cards, camera event rows, add-place and add-option cards |
| 3 — tile | smallest tile, chip, well or read-out | KPI tiles, camera health tiles, capability/lineage chips, count badges, code blocks |

- A layer is four tokens: `--layer-N-fill`, `-edge`, `-shadow`, `-border-width`.
  Defaults derive from each theme's own `--bg / --bg-secondary / --bg-tertiary /
  --border`, so a theme is consistent without extra work.
- A theme that wants more overrides **tokens**, never individual classes: 80s Neon
  makes the layers pink (`--accent`) → `#ffff66` → `#7fffd4` with limited glow;
  Asteroid City makes them hard postcard shadows.
- To add a component: add its class to the matching `:is()` list in `styles.css`.
  Never hard-code a background or border color on a surface — a guard test
  (`ThemeLayers.test.jsx`) fails the build if one comes back.
- A layer never recolors text. Fills stay dark/derived, so each theme's own text
  color reads; contrast was checked per theme (text ≥ 7:1 on all three layers in
  80s Neon; the only sub-4.5:1 pairs are themes' own muted-text colors, e.g.
  Monolith).
- Selected/hover/status states sit on top of the layers via tokens
  (`--layer-selected-edge`, `--layer-hover-edge`, KPI ok/warn/alarm edges).
- Controls (buttons, dropdowns, inputs) are not surfaces; they use the
  `--control-*` tokens.

**Tab buttons** (left nav rail, view tabs) are one color per theme
(`--tab-color`) in every state. A tab with a warn notification shows a dot badge
only — its icon is never tinted; a critical tab keeps its red bar, label and
pulse; the active tab is marked by its outline and tint, not by a different
text color. 80s Neon: every tab is `#ffff66`.

**Notification dots must follow the same list as the banner.** The rail, the
attention banner and the Status Checks list all derive from
`mergeStatusCheckItems()` (via `navAlertLevelsFor()`), so ignored/snoozed alerts,
other locations and automation alerts count identically everywhere.

**Semantic colors vs structural colors.** In 80s Neon yellow and aquamarine are
structural (layers, tabs); status is carried by the icon, wording and badge shape,
and danger stays pink-red. See `docs/retro-theme-fonts.md` for the earlier
semantic assignment this supersedes for surfaces.

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
