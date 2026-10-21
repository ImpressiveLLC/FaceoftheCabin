// Display preference only: never changes authentication or device permissions.
const KEY = 'familyHub.kiosk';
const requested = new URLSearchParams(location.search).get('kiosk');
let enabled = requested === '1';
if (requested !== '1' && requested !== '0') {
  try { enabled = localStorage.getItem(KEY) === '1'; } catch { /* optional storage */ }
}

function render() {
  document.documentElement.classList.toggle('kiosk-mode', enabled);
  document.querySelectorAll('[data-kiosk-toggle]').forEach(button => {
    button.textContent = enabled ? 'Exit kiosk' : 'Kiosk mode';
    button.setAttribute('aria-pressed', String(enabled));
  });
  document.querySelectorAll('[data-kiosk-fullscreen]').forEach(button => {
    button.hidden = !enabled || !!document.fullscreenElement || !document.documentElement.requestFullscreen;
  });
  const url = new URL(location.href);
  url.searchParams.set('kiosk', enabled ? '1' : '0');
  history.replaceState(history.state, '', url);
  try { localStorage.setItem(KEY, enabled ? '1' : '0'); } catch { /* optional storage */ }
}

async function fullscreen() {
  try { await document.documentElement.requestFullscreen?.(); } catch { /* keep usable windowed kiosk */ }
  render();
}

document.querySelectorAll('[data-kiosk-toggle]').forEach(button => {
  button.addEventListener('click', async () => {
    enabled = !enabled;
    render();
    if (enabled) await fullscreen();
    else if (document.fullscreenElement) {
      try { await document.exitFullscreen(); } catch { /* browser owns fullscreen */ }
    }
  });
});
document.querySelectorAll('[data-kiosk-fullscreen]').forEach(button => button.addEventListener('click', fullscreen));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && enabled) { enabled = false; render(); }
});
document.addEventListener('fullscreenchange', render);
document.querySelectorAll('[data-cabin-link]').forEach(link => {
  // Only display state crosses origins; credentials and the current URL never do.
  const update = () => {
    const url = new URL('https://cabin.unicornpingpong.com/');
    url.searchParams.set('kiosk', enabled ? '1' : '0');
    url.searchParams.set('theme', document.documentElement.dataset.theme || 'modern');
    link.href = url.href;
  };
  update();
  link.addEventListener('click', update);
});
render();
