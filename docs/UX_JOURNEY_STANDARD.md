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
