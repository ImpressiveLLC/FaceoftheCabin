// Origin-scoped, tab-scoped OAuth cache. Never copied into cross-app links.
window.HubSession = (() => {
  const key = 'familyHub.googleSession';
  const clear = () => { try { sessionStorage.removeItem(key); } catch {} };
  function read(clientId) {
    try {
      const value = JSON.parse(sessionStorage.getItem(key) || 'null');
      if (value && value.clientId === clientId && typeof value.token === 'string'
          && value.token && Number.isFinite(value.expiresAt) && value.expiresAt > Date.now() + 30000) return value;
    } catch { /* unavailable or malformed cache */ }
    clear();
    return null;
  }
  function save(clientId, token, expiresIn) {
    const seconds = Number(expiresIn);
    if (!token || !Number.isFinite(seconds) || seconds <= 30) { clear(); return; }
    try { sessionStorage.setItem(key, JSON.stringify({ clientId, token, expiresAt: Date.now() + seconds * 1000 })); } catch {}
  }
  return { read, save, clear };
})();
window.addEventListener('load', () => restoreGoogleSession());
