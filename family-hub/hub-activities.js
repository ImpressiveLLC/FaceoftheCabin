// Activity controller for the non-mobile Family Hub layout (see hub-activities.css).
// One activity is in focus at a time: chores, notes, dashboard or settings.
// html[data-activity] mirrors it. Off-click, a Close control or Escape returns
// to the compressed layout. `?layout=classic` keeps the previous docked layout.
(() => {
  const params = new URLSearchParams(location.search);
  if (params.get('layout') === 'classic') document.documentElement.classList.add('hub-classic');

  const wide = window.matchMedia('(min-width: 901px)');
  const root = document.documentElement;
  const isCompact = () => wide.matches && !root.classList.contains('hub-classic');
  const $ = id => document.getElementById(id);

  const activities = {
    chores: {
      panel: () => $('chores-card'),
      isOpen: () => $('chores-card')?.classList.contains('activity-open'),
      open: () => $('chores-card')?.classList.add('activity-open'),
      close: () => $('chores-card')?.classList.remove('activity-open'),
    },
    notes: {
      panel: () => $('notepad-panel'),
      isOpen: () => $('notepad-panel')?.classList.contains('open'),
      open: () => { toggleNotepad(true); setNotepadUnread(false); },
      close: () => toggleNotepad(false),
    },
    dashboard: {
      panel: () => $('dashboard-overlay'),
      isOpen: () => $('dashboard-overlay')?.classList.contains('open'),
      open: () => openDashboard(),
      close: () => closeDashboard(),
    },
    settings: {
      panel: () => $('settings-panel'),
      isOpen: () => $('settings-panel')?.classList.contains('open'),
      open: () => toggleSettings(),
      close: () => toggleSettings(),
    },
  };
  const railButton = id => document.querySelector(`[data-activity-btn="${id}"]`);

  function current() {
    return Object.keys(activities).find(id => activities[id].isOpen()) || '';
  }

  function sync() {
    const id = isCompact() ? current() : '';
    if (id) root.dataset.activity = id; else delete root.dataset.activity;
    Object.keys(activities).forEach(key => railButton(key)?.setAttribute('aria-expanded', String(key === id)));
  }

  function open(id) {
    Object.keys(activities).forEach(key => { if (key !== id && activities[key].isOpen()) activities[key].close(); });
    if (!activities[id].isOpen()) activities[id].open();
    sync();
  }
  function close() {
    const id = current();
    if (id) activities[id].close();
    sync();
  }

  function setNotepadUnread(unread) {
    const button = railButton('notes');
    if (button) button.dataset.unread = String(!!unread);
  }

  // Rail buttons: toggle the activity. Capture phase so the legacy inline
  // onclick on Dashboard/Settings does not also fire.
  document.addEventListener('click', event => {
    if (!isCompact()) return;
    const path = event.composedPath ? event.composedPath() : [event.target];
    const button = path.find(node => node.dataset && node.dataset.activityBtn);
    if (button) {
      event.preventDefault();
      event.stopPropagation();
      const id = button.dataset.activityBtn;
      if (activities[id].isOpen()) close(); else open(id);
      return;
    }
    if (path.some(node => node.dataset && node.dataset.activityClose !== undefined)) { close(); return; }
    // Off-click: anything outside the focused panel returns to the compressed layout.
    const id = current();
    if (!id) return;
    const inside = path.some(node => node === activities[id].panel() || node.id === 'activity-rail' || node.id === 'note-history-overlay');
    if (!inside) close();
  }, true);

  // Escape closes the focused activity first; kiosk.js only sees Escape when none is open.
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || !isCompact() || !current()) return;
    event.stopImmediatePropagation();
    close();
  }, true);

  // Anything that opens or closes a panel by another route (auth link, mobile
  // dock, a note posted elsewhere) still keeps one-at-a-time and the data attribute.
  const observer = new MutationObserver(() => {
    if (!isCompact()) { sync(); return; }
    const openIds = Object.keys(activities).filter(id => activities[id].isOpen());
    if (openIds.length > 1) {
      const keep = openIds.includes(root.dataset.activity) ? openIds.find(id => id !== root.dataset.activity) : openIds[0];
      openIds.filter(id => id !== keep).forEach(id => activities[id].close());
    }
    sync();
  });
  ['chores', 'notes', 'dashboard', 'settings'].forEach(id => {
    const panel = activities[id].panel();
    if (panel) observer.observe(panel, { attributes: true, attributeFilter: ['class'] });
  });

  // A note arriving must not take over a wall display: flag the Notes tab instead.
  const classicArrived = window.onNewNoteArrived;
  window.onNewNoteArrived = function (ts) {
    if (!isCompact()) return classicArrived.call(this, ts);
    notepadState.lastNoteTs = ts;
    notepadState.forcedCollapseDone = false;
    saveNotepadState();
    if (!activities.notes.isOpen()) setNotepadUnread(true);
  };

  // A "Done" bar at the bottom of every focus box: people arrive trained to look for an
  // exit inside the box, and the bottom is where they end up after reading or scrolling.
  const footHosts = {
    chores: () => $('chores-card'),
    notes: () => $('notepad-main-inner'),
    dashboard: () => $('dashboard-overlay'),
    settings: () => $('settings-panel'),
  };
  function addFooters() {
    Object.entries(footHosts).forEach(([id, host]) => {
      const el = host();
      if (!el || el.querySelector(':scope > .activity-foot')) return;
      const foot = document.createElement('div');
      foot.className = 'activity-foot';
      foot.innerHTML = '<button type="button" class="activity-done" data-activity-close>Done — back to the hub</button>' +
        '<span class="activity-foot-hint" aria-hidden="true">or tap anywhere outside, or swipe right</span>';
      el.appendChild(foot);
    });
  }

  // Swipe right (the way the box slid in) dismisses it on touch screens.
  let swipe = null;
  document.addEventListener('touchstart', event => {
    const id = isCompact() && current();
    const t = event.touches[0];
    if (!id || event.touches.length !== 1 || !activities[id].panel().contains(event.target) || event.target.closest('input, textarea, select, [contenteditable]')) { swipe = null; return; }
    swipe = { x: t.clientX, y: t.clientY };
  }, { passive: true });
  document.addEventListener('touchend', event => {
    if (!swipe) return;
    const t = event.changedTouches[0];
    const dx = t.clientX - swipe.x, dy = Math.abs(t.clientY - swipe.y);
    swipe = null;
    if (dx > 110 && dy < dx / 2) close();
  }, { passive: true });

  function start() {
    addFooters();
    if (isCompact() && typeof notepadState !== 'undefined' && notepadState.open) toggleNotepad(false); // always load compressed
    sync();
  }
  wide.addEventListener('change', () => { if (!isCompact()) close(); sync(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  window.hubActivities = { open, close, current };
})();
