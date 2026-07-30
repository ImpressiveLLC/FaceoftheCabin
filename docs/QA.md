# QA / Testing

Per-feature test coverage for this repo. Add a section per feature as it
gains test coverage — this is not meant to be exhaustive on day one.

---

## Family Hub: Family Notepad overlay

Spec: [`docs/PRODUCT_NOTES.md`](PRODUCT_NOTES.md) § "2026-07-30 — Family Hub: Family Notepad Overlay".
Code: `family-hub/family-hub.html` (CSS + markup + vanilla JS, no build step).

### Automated coverage

`family-hub/test/run.js` — a Playwright script that serves `family-hub.html`
locally and drives the real page (not a mock). Covers:

- Default state is slid-in (collapsed)
- Slid-out / slid-in widths are read live from `#chores-card` / `#dashboard-fab`
  / `#settings-btn` and match the largest / smallest of those, respectively
- The collapsed handle is fully on-screen and clickable (regression guard —
  an earlier flex/translateX layout bug pushed it entirely off-viewport;
  see git history on this file for the fix)
- Manual slide-out (handle click) and slide-in (chevron) both work
- Composing a note appends it to the list and clears the input
- A new note's default action is to auto-slide the panel out
- 24 hours after a note arrives, the panel force-collapses even if a user
  left it open — independent of any manual toggling in between
- The recent view caps at 12 messages
- Storage caps at 50 messages, oldest dropped first
- The history overlay shows all 50 saved messages
- The "last 50 notes are saved" hint is present
- No JS console errors during the run

**Run it — Docker (recommended, no host dependencies):**

```bash
cd family-hub
docker build -f test/Dockerfile -t family-hub-qa .
docker run --rm family-hub-qa
```

This is the form CI (or any machine — dev laptop, the M920q, a future
runner) should use: everything (Node, Playwright, Chromium, its system
deps) is pinned inside the image, so it's reproducible regardless of host
OS. This is also how we caught and fixed a real layout bug during
development — the host machine's OS wasn't officially supported by
Playwright's local install, which is exactly the kind of drift Docker
removes. Exit code is `0` on all-pass, `1` on any failure — safe to gate a
pipeline on.

**Run it — local Node (faster iteration while developing):**

```bash
cd family-hub
npm install                        # first time only — installs Playwright
npx playwright install chromium    # first time only — downloads the browser
npm test
```

### Manual QA checklist (not automated)

- [ ] Sign-in flow still renders correctly with the notepad panel present
      (the panel sits above the auth overlay by design — same pattern as the
      existing Dashboard/Settings FABs)
- [ ] Visual check across all 7 theme presets (Settings → Theme) — the panel
      uses theme CSS variables (`--glass`, `--gold`, `--teal`, etc.) so it
      should re-skin automatically, but confirm no contrast issues
- [ ] Narrow/kiosk-portrait viewport: panel repositions to bottom-anchored
      per the `@media (max-width: 900px)` rule and doesn't overlap the FABs
- [ ] Real 24-hour wait: leave a note, leave the tab open (or reload after
      the system clock advances 24h+), confirm it slides in on its own —
      the automated test fakes the elapsed time for speed, so this is the
      one behavior worth confirming against a real clock at least once
- [ ] Multi-tab/device check: open the same URL in two browsers — notes and
      open/collapsed state do **not** sync between them today (localStorage
      is per-browser; this is a known limitation, see PRODUCT_NOTES.md)

### Known gaps

- No test yet for the responsive/mobile CSS breakpoint's actual visual
  layout (checked manually via screenshot during development, not asserted
  in `run.js`)
- No CI wiring yet — `npm test` is local-only until the CI/CD to-do in
  `CLAUDE.md` is built
