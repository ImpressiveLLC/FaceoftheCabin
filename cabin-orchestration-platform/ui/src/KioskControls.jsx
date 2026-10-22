import React, { useEffect, useState } from 'react';
import { useTheme } from './ThemeProvider.jsx';

const KEY = 'cabin.kiosk';
export function initialKiosk() {
  const requested = new URLSearchParams(window.location.search).get('kiosk');
  if (requested === '1' || requested === '0') return requested === '1';
  try { return localStorage.getItem(KEY) === '1'; } catch { return false; }
}

export function hubLink(base, enabled, theme) {
  try {
    const url = new URL(base || 'https://hub.unicornpingpong.com/');
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
    url.searchParams.set('kiosk', enabled ? '1' : '0');
    url.searchParams.set('theme', theme);
    return url.href;
  } catch { return null; }
}

export default function KioskControls({ familyHubUrl, showAppLink = true }) {
  const [enabled, setEnabled] = useState(initialKiosk);
  const [full, setFull] = useState(!!document.fullscreenElement);
  const { themeId } = useTheme();
  const href = hubLink(familyHubUrl, enabled, themeId);
  useEffect(() => {
    document.documentElement.classList.toggle('kiosk-mode', enabled);
    try { localStorage.setItem(KEY, enabled ? '1' : '0'); } catch { /* optional storage */ }
    const url = new URL(window.location.href);
    url.searchParams.set('kiosk', enabled ? '1' : '0');
    window.history.replaceState(window.history.state, '', url);
    return () => document.documentElement.classList.remove('kiosk-mode');
  }, [enabled]);
  useEffect(() => {
    const change = () => setFull(!!document.fullscreenElement);
    const escape = event => { if (event.key === 'Escape') setEnabled(false); };
    document.addEventListener('fullscreenchange', change);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('fullscreenchange', change);
      document.removeEventListener('keydown', escape);
    };
  }, []);
  async function fullscreen() {
    try { await document.documentElement.requestFullscreen?.(); } catch { /* windowed kiosk remains usable */ }
  }
  async function toggle() {
    setEnabled(!enabled);
    if (!enabled) await fullscreen();
    else if (document.fullscreenElement) {
      try { await document.exitFullscreen(); } catch { /* browser owns fullscreen */ }
    }
  }
  return <div className="display-controls" role="group" aria-label="Display and apps">
    <button type="button" className="kiosk-switch" role="switch" aria-label="Kiosk" onClick={toggle} aria-checked={enabled}><span>Kiosk</span><span className="kiosk-slider" aria-hidden="true" /></button>
    {enabled && !full && document.documentElement.requestFullscreen && <button type="button" onClick={fullscreen} aria-label="Fullscreen" title="Fullscreen">⛶</button>}
    {showAppLink && href && <a href={href}>Family Hub</a>}
  </div>;
}

import './touch-scroll.js';

import './touch-display.css';
