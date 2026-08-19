// Basic automated QA for the Family Notepad overlay in family-hub.html.
// Usage: cd family-hub && npm install && npm test
//
// Spins up a plain static file server for this directory, drives the page
// with Playwright, and asserts the notepad's core behavioral contract:
// default collapsed state, width-matching against real right-side UI
// elements, manual toggle, auto-open on a new note, forced 24h auto-collapse,
// the 12-message recent view, and the 50-message history cap.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
const PORT = 8791;

const MIME = { '.html': 'text/html', '.css': 'text/css', '.svg': 'image/svg+xml', '.js': 'text/javascript' };

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const filePath = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
      fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end('not found'); return; }
        const ext = path.extname(filePath);
        res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
        res.end(data);
      });
    });
    server.listen(PORT, () => resolve(server));
  });
}

let passed = 0, failed = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `  (expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)})`}`);
  if (ok) passed++; else failed++;
}

(async () => {
  const server = await startServer();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  const jsErrors = [];
  page.on('pageerror', e => jsErrors.push(e.message));
  page.on('console', msg => { if (msg.type() === 'error') jsErrors.push(msg.text()); });

  await page.goto(`http://localhost:${PORT}/family-hub.html`, { waitUntil: 'load' });
  await page.waitForTimeout(400);
  // The remaining journey exercises the signed-in hub surface; auth itself is
  // covered separately in the mobile layering check below.
  await page.evaluate(() => document.getElementById('auth-overlay').classList.add('hidden'));

  // Default state is slid-in (collapsed), not open.
  check('default state is slid-in',
    await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Collapsed width is a fixed constant, not derived from other elements'
  // layout (see computeNotepadWidths()'s own comment for why: it used to
  // be, which caused the tucked-in handle to end up wider than intended
  // and block other UI -- a user-reported bug). Widened 132px -> 190px on
  // 2026-08-15 so the collapsed handle could align its right edge with
  // #chores-card/#dashboard-fab/#settings-btn (all newly aligned to a
  // shared right:30px/width:310px that same day) and fit horizontal text
  // instead of the 2026-08-07 vertical-rl rotation workaround -- still a
  // fixed, independent constant, just a different one.
  //
  // Expanded width is still derived from real right-side elements, but as
  // of 2026-08-15 with a floor: collapsed width + NOTEPAD_MIN_CONTENT_WIDTH
  // (200px), so #notepad-main-inner (literally expanded-width minus
  // collapsed-width) always keeps real room for the compose box/list even
  // if collapsed width grows close to or past the reference elements'
  // measured width -- see computeNotepadWidths()'s own comment.
  const widths = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    return { expanded: cs.getPropertyValue('--np-w-expanded').trim(), collapsed: cs.getPropertyValue('--np-w-collapsed').trim() };
  });
  const refWidths = await page.evaluate(() =>
    ['chores-card', 'dashboard-fab', 'settings-btn']
      .map(id => document.getElementById(id).getBoundingClientRect().width));
  const expectedCollapsed = 190;
  const expectedExpanded = Math.max(Math.max(...refWidths), expectedCollapsed + 200);
  check('slid-out width == max(largest right-side element, collapsed width + content floor)',
    widths.expanded, `${Math.round(expectedExpanded)}px`);
  check('slid-in width is the fixed, narrow constant (not derived from other elements)',
    widths.collapsed, `${expectedCollapsed}px`);

  // The collapsed handle must actually be on-screen and clickable (regression
  // guard for the flex/translateX bug this suite was written to catch).
  const handleBox = await page.locator('#notepad-handle').boundingBox();
  const vw = page.viewportSize().width;
  check('collapsed handle is fully on-screen', handleBox.x >= 0 && handleBox.x + handleBox.width <= vw + 1, true);

  // Regression guard, added 2026-08-07, for a real user-reported bug: the
  // tucked-in notepad visibly covered #chores-card. Root cause was
  // computeNotepadTop() treating a *hidden* element's getBoundingClientRect
  // (mobile-action-dock, display:none on desktop, so top:0) as a real
  // position, poisoning the "how much room is below" math down to ~0 --
  // which a plain on-screen/bounding-box check doesn't catch, since a
  // clipped child still reports its own intended geometry. Two direct
  // checks: the panel must have real, non-zero height, and it must not
  // geometrically overlap #chores-card at all -- the actual hard
  // constraint the user asked for ("anything but covering the other
  // critical ui elements").
  const panelBox = await page.locator('#notepad-panel').boundingBox();
  check('notepad panel has real, non-zero rendered height', panelBox.height > 0, true);
  const choresBox = await page.locator('#chores-card').boundingBox();
  const overlapsChores = panelBox.y < choresBox.y + choresBox.height
    && panelBox.y + panelBox.height > choresBox.y
    && panelBox.x < choresBox.x + choresBox.width
    && panelBox.x + panelBox.width > choresBox.x;
  check('notepad panel never overlaps #chores-card, even tucked in', overlapsChores, false);

  // Manual slide-out / slide-in control.
  await page.locator('#notepad-handle').click();
  await page.waitForTimeout(500);
  check('handle click slides panel out', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);

  // Sending a note requires an explicit acting identity first -- no
  // silent default to the first profile (see docs/DEFINITION_OF_DONE.md's
  // 2026-08-02 note-attribution fix).
  check('composer is blocked with no actor selected',
    await page.locator('#notepad-input').isDisabled(), true);
  check('send button is disabled with no actor selected',
    await page.locator('.np-send').isDisabled(), true);
  check('"who\'s leaving this note" prompt is visible with no actor selected',
    await page.locator('#notepad-author-prompt').isVisible(), true);

  // Pick an identity via the notepad's own author row -- this must set the
  // *global* acting context (setCurrentActor), not a note-local variable.
  await page.locator('.np-author-pill').first().click();
  await page.waitForTimeout(100);
  check('prompt hides once an actor is selected',
    await page.locator('#notepad-author-prompt').isVisible(), false);
  check('composer enables once an actor is selected',
    await page.locator('#notepad-input').isDisabled(), false);

  // Compose a note.
  await page.locator('#notepad-input').fill('Pick up milk on the way home!');
  await page.locator('.np-send').click();
  await page.waitForTimeout(200);
  check('sent note appears in the list', await page.locator('#notepad-list .np-row').count() >= 1, true);
  check('input clears after send', await page.locator('#notepad-input').inputValue(), '');
  check('sent note is attributed to the selected actor, not a hardcoded default',
    await page.evaluate(() => loadNotes()[0].authorId), await page.evaluate(() => currentActorId));

  // Actor expiry must clear note attribution too -- they're the same
  // variable now, not two that can independently drift.
  await page.evaluate(() => clearActorState());
  await page.evaluate(() => renderNoteAuthorRow());
  check('composer re-blocks after the actor is cleared',
    await page.locator('#notepad-input').isDisabled(), true);

  // Re-select an actor so the rest of the suite (which assumes a working
  // composer) isn't left in the blocked state.
  await page.locator('.np-author-pill').first().click();
  await page.waitForTimeout(100);

  // Manual slide-in control (chevron).
  await page.locator('.np-close').click();
  await page.waitForTimeout(500);
  check('close chevron slides panel in', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Default action on a new note is to slide out automatically.
  await page.evaluate(() => {
    const notes = loadNotes();
    notes.unshift({ id: 'test-auto-open', authorId: profiles[0].id, text: 'Auto-open test note', ts: Date.now() });
    saveNotes(notes);
    onNewNoteArrived(notes[0].ts);
    renderNotepad();
  });
  await page.waitForTimeout(500);
  check('new note arrival auto-slides the panel out', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);

  // 24h-visible rule forces slide-in even without user action, independent
  // of whatever the user did with the manual control in the meantime.
  await page.evaluate(() => { toggleNotepad(true); });
  await page.evaluate(() => {
    notepadState.lastNoteTs = Date.now() - (25 * 60 * 60 * 1000);
    notepadState.forcedCollapseDone = false;
    saveNotepadState();
    checkNotepadAutoCollapse();
  });
  await page.waitForTimeout(300);
  check('panel force-collapses 24h after a note arrives', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Recent view caps at 12 lines; full history caps storage at 50.
  await page.evaluate(() => {
    let notes = [];
    for (let i = 0; i < 55; i++) notes.unshift({ id: 'n' + i, authorId: profiles[0].id, text: 'Note #' + i, ts: Date.now() - i * 1000 });
    saveNotes(notes);
    renderNotepad();
    toggleNotepad(true);
  });
  await page.waitForTimeout(400);
  check('recent list caps at 12 messages', await page.locator('#notepad-list .np-row').count(), 12);
  check('storage caps at 50 messages (oldest dropped)', await page.evaluate(() => loadNotes().length), 50);

  await page.locator('.np-history-link').click();
  await page.waitForTimeout(400);
  check('history overlay opens', await page.locator('#note-history-overlay').evaluate(el => el.classList.contains('open')), true);
  check('history shows all 50 saved messages', await page.locator('#note-history-list .np-row').count(), 50);
  check('"last 50 notes are saved" hint is present', await page.locator('#note-history-overlay .np-save-hint').isVisible(), true);

  // ═══════════════════════════════════════════
  // CHORE MANAGEMENT (2026-08-15) -- chore library/assignment CRUD, the
  // idempotent completion PUT, the condensed landing card for >2 kids,
  // and the add-child rerender fix. This harness has no live cabin-backend
  // (CABIN_API_URL/accessToken aren't configured), so choreLibrary/
  // choreAssignments start empty and any write attempt no-ops after a
  // failed fetch -- same constraint the notes/profiles checks above
  // already work within. Seeds directly into the in-page cache instead
  // (mirrors this file's own loadNotes/saveNotes seeding technique above)
  // to exercise the actual render/interaction logic without a real DB.
  // ═══════════════════════════════════════════

  // renderTodayChores() early-returns a "kids are at Mom's" message
  // instead of rendering any kid cards at all when isKidsHome(new Date())
  // is false for the real, current wall-clock date -- which depends on
  // the actual custody-rotation math and the container's own timezone
  // (found via a real CI failure: passed against local-timezone "now" on
  // one run, failed against the CI container's UTC "now" a few minutes
  // later, because the two clocks landed on different sides of the
  // custody day boundary). None of the checks below care what the real
  // custody schedule says today -- forcing isKidsHome() to always return
  // true here makes this deterministic regardless of what day or
  // timezone this suite happens to run in.
  await page.evaluate(() => { window.isKidsHome = () => true; });

  // Manage mode must render (all three sub-tabs) without throwing even
  // against a completely empty library/assignment cache -- the realistic
  // state for a brand-new instance before any seed migration has run.
  // #chore-manage-toggle lives inside the dashboard overlay's Chore
  // Tracker tab-pane, so that has to actually be open (not just present
  // in the DOM, which even a hidden tab-pane still is) before it's
  // clickable -- openDashboardTo() is this app's own helper for that.
  // The note-history overlay opened a few checks up is still open and
  // sitting on top of everything -- close it first or it intercepts every
  // click from here on.
  await page.evaluate(() => closeNoteHistory());
  await page.evaluate(() => { accessToken = 'test-token'; }); // renderChoreManage() gates on accessToken alone, not a real backend round-trip
  await page.evaluate(() => openDashboardTo('chores'));
  await page.waitForTimeout(150);
  await page.locator('#chore-manage-toggle').click();
  await page.waitForTimeout(100);
  check('manage chores toggle switches into manage mode',
    await page.locator('#chore-manage-body').evaluate(el => !el.classList.contains('hidden')), true);
  check('chore tracker body hides while in manage mode',
    await page.locator('#chore-tracker-body').evaluate(el => el.classList.contains('hidden')), true);
  await page.evaluate(() => switchChoreManageTab('assignments'));
  await page.waitForTimeout(50);
  await page.evaluate(() => switchChoreManageTab('today'));
  await page.waitForTimeout(50);
  await page.evaluate(() => switchChoreManageTab('library'));
  await page.waitForTimeout(50);
  check('cycling through all three manage sub-tabs with an empty cache produces no errors', jsErrors, []);
  await page.locator('#chore-manage-toggle').click();
  await page.waitForTimeout(100);
  check('manage chores toggle switches back to the tracker view',
    await page.locator('#chore-manage-body').evaluate(el => el.classList.contains('hidden')), true);

  // Seed a realistic chore library + assignment, shaped like what
  // ChoreDefinitionsController/ChoreAssignmentsController actually return.
  await page.evaluate(() => {
    choreLibrary = [{ id: 'test_chore', label: 'Test Chore', points: 1, minAge: 0, tags: ['test'], active: true }];
    choreAssignments = [{
      id: 'test-assignment-1', choreDefinitionId: 'test_chore', childId: profiles.find(p => p.role === 'kid').id,
      recurrence: 'DAILY', effectiveStart: '2000-01-01', effectiveEnd: null, displayOrder: 0,
      createdBy: 'seed', updatedBy: 'seed', createdAt: Date.now(), updatedAt: Date.now(),
    }];
    rerenderChoreViews();
  });
  await page.waitForTimeout(100);
  check('seeded assignment resolves through defaultAssignments() (backend-driven, not age/calendar-derived)',
    await page.evaluate(() => defaultAssignments(new Date().toISOString().slice(0,10), profiles.find(p => p.role === 'kid').id).some(c => c.id === 'test_chore')), true);
  check('seeded assignment renders as a real chore row in the chore tracker (Chore Tracker tab is currently active)',
    await page.locator('#chore-tracker-body .chore-full-label').first().innerText(), 'Test Chore');

  // With the starting 2 kids (sam/emma), the landing card must stay fully
  // expanded -- condensed mode only kicks in above 2. #today-chores-body
  // is the always-on-screen ambient card, not inside the dashboard
  // overlay, but close the overlay anyway so it can't visually cover it
  // for the click-outside check later in this block.
  await page.evaluate(() => closeDashboard());
  await page.waitForTimeout(100);
  check('with 2 kids, the landing card has no compact rows (present design preserved)',
    await page.locator('#today-chores-body .kid-section-compact').count(), 0);

  // Add-child rerender fix (2026-08-15 bug): saving a new profile must
  // immediately update the landing chores card and chore kid selector, not
  // just the Family tab -- previously required a manual reload even though
  // the save itself succeeded.
  await page.evaluate(() => openDashboardTo('profiles'));
  await page.waitForTimeout(150);
  await page.locator('button:has-text("+ Add Member")').click();
  await page.waitForTimeout(100);
  await page.locator('#pf-name').fill('Testy McTestface');
  await page.locator('#pf-age').fill('7');
  await page.locator('#profiles-form-col button:has-text("Save")').click();
  await page.waitForTimeout(300);
  check('a newly added child appears in the landing chores card immediately, no reload needed',
    (await page.locator('#today-chores-body').innerText()).includes('Testy'), true);
  await page.evaluate(() => switchTab('chores')); // chore-kid-selector is display:none on a non-active tab-pane -- innerText() would read '' regardless of DOM content
  await page.waitForTimeout(100);
  check('a newly added child appears in the chore kid selector immediately, no reload needed',
    (await page.locator('#chore-kid-selector').innerText()).includes('Testy'), true);

  // Condensed landing view (2026-08-15): adding that 3rd kid crosses the
  // >2 threshold -- must now render compact summary rows instead of the
  // old always-fully-expanded layout (rejected 40%-opacity alternative
  // aside, see renderTodayChores()'s own comment).
  await page.evaluate(() => closeDashboard());
  await page.waitForTimeout(100);
  check('with 3 kids, the landing card renders compact rows',
    await page.locator('#today-chores-body .kid-section-compact').count() >= 1, true);

  const firstCompactId = await page.locator('#today-chores-body [data-landing-kid-compact]').first().getAttribute('data-landing-kid-compact');
  await page.locator('#today-chores-body [data-landing-kid-compact]').first().click();
  await page.waitForTimeout(100);
  check('tapping a compact row expands that kid',
    await page.locator(`#today-chores-body [data-landing-kid-expanded="${firstCompactId}"]`).count(), 1);
  check('other kids stay compact instead of also expanding',
    await page.locator('#today-chores-body .kid-section-compact').count() >= 1, true);

  // Clicking outside the expanded kid (the card background, not a chore
  // row inside it) collapses it back -- capture-phase document listener,
  // same pattern as this app's own notepad click-outside-collapse.
  await page.locator('#custody-card').click();
  await page.waitForTimeout(100);
  check('clicking outside the expanded kid collapses it back to compact',
    await page.locator('#today-chores-body [data-landing-kid-expanded]').count(), 0);

  check('no JS errors from chore management, seeding, or the condensed landing view', jsErrors, []);

  check('no JS errors during the run', jsErrors, []);

  // Cross-app theme handoff (added 2026-08-07, Phase 7 §2c/2d -- see
  // docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md): the actual
  // reported bug was "theme resets when I link out to cabin-ui," root-caused
  // to origin-scoped localStorage between the two subdomains. Two directions
  // to prove: (1) the "How's the cabin?" link-out carries this app's current
  // theme forward, (2) loading with ?theme=<id> in the URL applies it here,
  // the same way cabin-ui's ThemeProvider now does on its side.
  check('location-links-out carries the active theme as a query param',
    await page.evaluate(() => locationLinksHtml().includes(`theme=${activeThemeId}`)), true);

  const themedPage = await browser.newPage();
  const themedJsErrors = [];
  themedPage.on('pageerror', e => themedJsErrors.push(e.message));
  await themedPage.goto(`http://localhost:${PORT}/family-hub.html?theme=asteroidcity`, { waitUntil: 'load' });
  check('?theme= URL param is applied on load, not just the last-saved localStorage value',
    await themedPage.evaluate(() => activeThemeId), 'asteroidcity');
  check('?theme= param application produces no JS errors', themedJsErrors, []);
  const themedControlStyles = await themedPage.evaluate(() => {
    const select = getComputedStyle(document.getElementById('set-interval'));
    const close = getComputedStyle(document.getElementById('dash-close'));
    const settings = getComputedStyle(document.getElementById('settings-panel'));
    const card = getComputedStyle(document.querySelector('.glass-card'));
    const banner = document.createElement('div');
    banner.className = 'chore-reward-banner';
    document.body.appendChild(banner);
    const bannerStyle = getComputedStyle(banner);
    const result = {
      accent: getComputedStyle(document.documentElement).getPropertyValue('--gold').trim(),
      selectBorderWidth: select.borderTopWidth,
      closeBorderWidth: close.borderTopWidth,
      closeBackground: close.backgroundColor,
      settingsOutline: settings.borderLeftColor,
      cardOutline: card.borderTopColor,
      bannerBorderWidth: bannerStyle.borderTopWidth,
    };
    banner.remove();
    return result;
  });
  check('Asteroid City uses the supplied blazing-orange accent', themedControlStyles.accent, '#e34e24');
  check('theme styling gives dropdowns a prominent outline', themedControlStyles.selectBorderWidth, '2px');
  check('Family Hub Close control receives the themed outline', themedControlStyles.closeBorderWidth, '2px');
  check('Family Hub Close control receives a themed surface',
    themedControlStyles.closeBackground === 'rgba(0, 0, 0, 0)', false);
  check('Settings outline matches the other Family Hub UI boxes',
    themedControlStyles.settingsOutline, themedControlStyles.cardOutline);
  check('theme styling reaches banners', themedControlStyles.bannerBorderWidth, '2px');

  await themedPage.close();

  // Phone journey: every high-frequency family action must be visible and
  // reachable without hunting through tabs or losing the current hub context.
  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const mobileJsErrors = [];
  mobile.on('pageerror', e => mobileJsErrors.push(e.message));
  mobile.on('console', msg => { if (msg.type() === 'error') mobileJsErrors.push(msg.text()); });
  await mobile.goto(`http://localhost:${PORT}/family-hub.html`, { waitUntil: 'load' });
  await mobile.waitForTimeout(400);

  check('sign-in context hides quick actions until the hub is available', await mobile.locator('#mobile-action-dock').isVisible(), false);
  await mobile.evaluate(() => document.getElementById('auth-overlay').classList.add('hidden'));

  check('mobile quick-action dock is visible', await mobile.locator('#mobile-action-dock').isVisible(), true);
  check('mobile dock exposes four single-tap actions', await mobile.locator('#mobile-action-dock .mobile-action').count(), 4);
  check('desktop dashboard FAB is replaced on mobile', await mobile.locator('#dashboard-fab').isVisible(), false);
  check('mobile page has no horizontal document overflow', await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);

  await mobile.getByRole('button', { name: 'View the parenting schedule' }).click();
  await mobile.waitForTimeout(150);
  check('schedule action opens dashboard', await mobile.locator('#dashboard-overlay').getAttribute('aria-hidden'), 'false');
  check('schedule action lands directly in Parenting Days', await mobile.locator('#tab-schedule').evaluate(el => el.classList.contains('active')), true);
  check('schedule remains horizontally scrollable instead of squeezing days unreadably',
    await mobile.locator('#schedule-grid').evaluate(el => el.scrollWidth > el.clientWidth), true);

  await mobile.locator('#dash-close').click();
  await mobile.getByRole('button', { name: 'Read or send a family note' }).click();
  await mobile.waitForTimeout(150);
  check('notes action opens composer in the same hub context', await mobile.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);
  const noteBox = await mobile.locator('#notepad-panel').boundingBox();
  const dockBox = await mobile.locator('#mobile-action-dock').boundingBox();
  check('open note composer stays above the mobile action dock', noteBox.y + noteBox.height <= dockBox.y + 1, true);

  await mobile.getByRole('button', { name: 'Open the family dashboard' }).click();
  await mobile.waitForTimeout(150);
  check('overview secondary cards collapse for one-screen scanning', await mobile.locator('#tab-overview .dash-card.mobile-collapsed').count() > 0, true);
  check('next-seven-days schedule stays expanded by default',
    await mobile.locator('#tab-overview .dash-card').filter({ hasText: 'Next 7 Days' }).evaluate(el => !el.classList.contains('mobile-collapsed')), true);
  check('unconfigured cabin activity explains why instead of disappearing',
    (await mobile.locator('#cabin-activity-widget').innerText()).includes('not connected on this device'), true);
  check('unavailable house capability explains its status onscreen',
    (await mobile.locator('.location-link-disabled').innerText()).includes('working template'), true);
  check('no JS errors during mobile journey', mobileJsErrors, []);

  await mobile.close();

  await browser.close();
  await new Promise(r => server.close(r));

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})();
