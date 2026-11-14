// Compressed layout + focused activities (hub-activities.css / hub-activities.js),
// the pinned Settings control and the small presence pill.
// Runs at the kiosk resolution and typical desktop sizes; mobile must be unchanged.
module.exports = async function ({ browser, check, base }) {
  const ACTIVITIES = { chores: '#chores-card', notes: '#notepad-panel', dashboard: '#dashboard-overlay', settings: '#settings-panel' };
  const errors = [];

  async function open(width, height, query) {
    const page = await browser.newPage({ viewport: { width, height } });
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(`${base}/family-hub.html${query}`);
    await page.evaluate(() => {
      document.getElementById('auth-overlay').classList.add('hidden');
      document.getElementById('presence-badge').style.display = 'flex';
    });
    await page.waitForTimeout(1600); // #app fade-in
    return page;
  }

  const activity = page => page.evaluate(() => document.documentElement.dataset.activity || '');
  const settingsGeometry = page => page.evaluate(() => {
    const el = document.getElementById('settings-btn');
    const r = el.getBoundingClientRect();
    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    const s = getComputedStyle(el);
    return {
      visible: s.display !== 'none' && s.visibility === 'visible' && r.width > 0,
      inViewport: r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
      uncovered: !!hit && (hit === el || el.contains(hit)),
      position: s.position,
    };
  });

  // Settings is pinned at the kiosk resolution and at typical desktop sizes, kiosk or not.
  for (const [w, h, q] of [[1024, 600, '?kiosk=1'], [1280, 720, '?kiosk=1'], [1366, 768, '?kiosk=0'], [1920, 1080, '?kiosk=0'], [1280, 720, '?kiosk=1&layout=classic']]) {
    const page = await open(w, h, q);
    const tag = `${w}x${h}${q}`;
    const g = await settingsGeometry(page);
    check(`Settings visible and pinned at ${tag}`, [g.visible, g.inViewport, g.position], [true, true, 'fixed']);
    check(`Settings not covered by other UI at ${tag}`, g.uncovered, true);
    // Content that overflows the page must never push the pinned control away.
    await page.evaluate(() => { const app = document.getElementById('app'); app.style.minHeight = '3000px'; app.scrollTop = 500; });
    check(`Settings stays pinned with an overflowing app at ${tag}`, (await settingsGeometry(page)).inViewport, true);
    const pill = await page.locator('#presence-badge').boundingBox();
    check(`presence status is a small pill at ${tag}`, pill.height <= 28 && pill.width <= 170, true);
    await page.close();
  }

  // Compressed default, focus geometry, off-click and close paths.
  for (const [w, h, q] of [[1024, 600, '?kiosk=1'], [1280, 720, '?kiosk=1'], [1920, 1080, '?kiosk=0']]) {
    const tag = `${w}x${h}`;
    const kioskOn = q.includes('kiosk=1');
    const page = await open(w, h, q);
    check(`loads compressed (no activity in focus) at ${tag}`, await activity(page), '');
    check(`four activities offered at ${tag}`, await page.locator('#activity-rail .rail-btn, #dashboard-fab, #settings-btn').count(), 4);
    for (const sel of Object.values(ACTIVITIES)) {
      check(`${sel} hidden while compressed at ${tag}`, await page.locator(sel).isVisible(), false);
    }
    const reservedRight = await page.evaluate(() => Math.max(...['left-col', 'kiosk-controls'].map(id => document.getElementById(id).getBoundingClientRect().right)));
    const boxStyle = {};
    for (const [name, sel] of Object.entries(ACTIVITIES)) {
      await page.click(`[data-activity-btn="${name}"]`);
      await page.waitForTimeout(650);
      check(`${name} takes focus at ${tag}`, await activity(page), name);
      const box = await page.locator(sel).boundingBox();
      const railLeft = Math.min((await page.locator('#dashboard-fab').boundingBox()).x, (await page.locator('#activity-rail').boundingBox()).x);
      check(`${name} never covers the reserved left column or kiosk controls at ${tag}`, box.x >= reservedRight - 1, true);
      check(`${name} leaves the rail uncovered at ${tag}`, box.x + box.width <= railLeft + 1, true);
      check(`${name} expands over most of the screen height at ${tag}`, box.height >= h * 0.8, true);
      check(`${name} uses spare horizontal space at ${tag}`, box.width >= w * 0.4, true);
      check(`Settings stays visible while ${name} is in focus at ${tag}`, (await settingsGeometry(page)).uncovered, true);
      boxStyle[name] = await page.locator(sel).evaluate(el => { const s = getComputedStyle(el); return [s.borderTopWidth, s.borderTopColor, s.borderTopLeftRadius].join('|'); });
      // Off-click on the calendar column (outside the box and the rail) returns to compressed.
      await page.locator('#cal-card').click();
      await page.waitForTimeout(500);
      check(`off-click collapses ${name} at ${tag}`, [await page.locator(sel).isVisible(), await activity(page)], [false, '']);
    }
    check(`Settings box matches the other overlays at ${tag}`, boxStyle.settings, boxStyle.chores);
    check(`Notes box matches the other overlays at ${tag}`, boxStyle.notes, boxStyle.chores);
    check(`Dashboard box matches the other overlays at ${tag}`, boxStyle.dashboard, boxStyle.chores);

    // Exactly one activity in focus; the rail button toggles it away.
    await page.click('[data-activity-btn="chores"]');
    await page.click('[data-activity-btn="settings"]');
    await page.waitForTimeout(600);
    check(`one activity at a time at ${tag}`, [await activity(page), await page.locator('#chores-card').isVisible()], ['settings', false]);
    await page.click('[data-activity-btn="settings"]');
    await page.waitForTimeout(500);
    check(`rail button toggles the activity back at ${tag}`, await activity(page), '');

    // Close control and Escape.
    await page.click('[data-activity-btn="chores"]');
    await page.waitForTimeout(500);
    await page.locator('#chores-card .chores-close').click();
    await page.waitForTimeout(500);
    check(`chores Close returns to compressed at ${tag}`, [await page.locator('#chores-card').isVisible(), await activity(page)], [false, '']);
    await page.click('[data-activity-btn="dashboard"]');
    await page.waitForTimeout(500);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    check(`Escape closes the focused activity first at ${tag}`,
      [await activity(page), await page.evaluate(() => document.documentElement.classList.contains('kiosk-mode'))], ['', kioskOn]);
    if (kioskOn) {
      await page.keyboard.press('Escape');
      check(`second Escape still exits kiosk at ${tag}`, await page.evaluate(() => document.documentElement.classList.contains('kiosk-mode')), false);
    }
    await page.close();
  }

  // A new note must not take over the display: it flags the Notes tab instead.
  const notes = await open(1280, 720, '?kiosk=1');
  await notes.evaluate(() => onNewNoteArrived(Date.now()));
  await notes.waitForTimeout(300);
  check('new note does not open Notes while compressed', await notes.locator('#notepad-panel').isVisible(), false);
  check('new note flags the Notes tab', await notes.locator('[data-activity-btn="notes"]').getAttribute('data-unread'), 'true');
  await notes.click('[data-activity-btn="notes"]');
  await notes.waitForTimeout(500);
  check('opening Notes clears the flag', await notes.locator('[data-activity-btn="notes"]').getAttribute('data-unread'), 'false');
  await notes.reload();
  await notes.waitForTimeout(800);
  check('reload returns to the compressed layout', [await notes.locator('#notepad-panel').isVisible(), await activity(notes)], [false, '']);
  await notes.close();

  // Mobile is unchanged: no rail, the dock still owns Settings, no horizontal overflow.
  const mobile = await open(390, 844, '?kiosk=1');
  check('mobile has no activity rail', await mobile.locator('#activity-rail').isVisible(), false);
  check('mobile keeps the action dock', await mobile.locator('#mobile-action-dock').isVisible(), true);
  check('mobile hides the desktop Settings pin', await mobile.locator('#settings-btn').isVisible(), false);
  check('mobile layout does not overflow horizontally', await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
  await mobile.close();

  const classic = await open(1280, 720, '?layout=classic');
  check('classic layout opt-out has no rail', await classic.locator('#activity-rail').isVisible(), false);
  await classic.close();

  check('no JS errors from activity layout journeys', errors, []);
};
