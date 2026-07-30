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

  // Default state is slid-in (collapsed), not open.
  check('default state is slid-in',
    await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), false);

  // Widths are computed from real right-side elements, not hardcoded.
  const widths = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    return { expanded: cs.getPropertyValue('--np-w-expanded').trim(), collapsed: cs.getPropertyValue('--np-w-collapsed').trim() };
  });
  const refWidths = await page.evaluate(() =>
    ['chores-card', 'dashboard-fab', 'settings-btn']
      .map(id => document.getElementById(id).getBoundingClientRect().width));
  check('slid-out width == largest right-side element', widths.expanded, `${Math.round(Math.max(...refWidths))}px`);
  check('slid-in width == smallest right-side element', widths.collapsed, `${Math.round(Math.min(...refWidths))}px`);

  // The collapsed handle must actually be on-screen and clickable (regression
  // guard for the flex/translateX bug this suite was written to catch).
  const handleBox = await page.locator('#notepad-handle').boundingBox();
  const vw = page.viewportSize().width;
  check('collapsed handle is fully on-screen', handleBox.x >= 0 && handleBox.x + handleBox.width <= vw + 1, true);

  // Manual slide-out / slide-in control.
  await page.locator('#notepad-handle').click();
  await page.waitForTimeout(500);
  check('handle click slides panel out', await page.locator('#notepad-panel').evaluate(el => el.classList.contains('open')), true);

  // Compose a note.
  await page.locator('#notepad-input').fill('Pick up milk on the way home!');
  await page.locator('.np-send').click();
  await page.waitForTimeout(200);
  check('sent note appears in the list', await page.locator('#notepad-list .np-row').count() >= 1, true);
  check('input clears after send', await page.locator('#notepad-input').inputValue(), '');

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

  check('no JS errors during the run', jsErrors, []);

  await browser.close();
  await new Promise(r => server.close(r));

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})();
