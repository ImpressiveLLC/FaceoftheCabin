import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import KioskControls, { hubLink, initialKiosk } from './KioskControls.jsx';
vi.mock('./ThemeProvider.jsx', () => ({ useTheme: () => ({ themeId: 'modern' }) }));
beforeEach(() => {
  localStorage.clear();
  window.history.replaceState({}, '', '/?panel=CAMERA_EVENTS');
  document.documentElement.requestFullscreen = vi.fn().mockRejectedValue(new Error('denied'));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); delete document.documentElement.requestFullscreen; });

describe('kiosk display and app navigation', () => {
  it('toggles and persists even when fullscreen is rejected, preserving the panel', async () => {
    render(<KioskControls />);
    fireEvent.click(screen.getByRole('button', { name: 'Kiosk mode' }));
    await waitFor(() => expect(localStorage.getItem('cabin.kiosk')).toBe('1'));
    expect(document.documentElement.classList.contains('kiosk-mode')).toBe(true);
    expect(new URL(location.href).searchParams.get('panel')).toBe('CAMERA_EVENTS');
    const link = screen.getByRole('link', { name: 'Family Hub' });
    expect(link.getAttribute('href')).toBe('https://hub.unicornpingpong.com/?kiosk=1&theme=modern');
    expect(link.hasAttribute('target')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Exit kiosk' }));
    await waitFor(() => expect(localStorage.getItem('cabin.kiosk')).toBe('0'));
  });
  it('honors explicit off before saved state and explicit on before storage', () => {
    localStorage.setItem('cabin.kiosk', '1');
    window.history.replaceState({}, '', '/?kiosk=0');
    expect(initialKiosk()).toBe(false);
    localStorage.setItem('cabin.kiosk', '0');
    window.history.replaceState({}, '', '/?kiosk=1');
    expect(initialKiosk()).toBe(true);
  });
  it('restores saved state and exits with Escape without hiding the exit path', () => {
    localStorage.setItem('cabin.kiosk', '1');
    render(<KioskControls />);
    expect(screen.getByRole('button', { name: 'Exit kiosk' })).toBeTruthy();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByRole('button', { name: 'Kiosk mode' })).toBeTruthy();
    expect(document.documentElement.classList.contains('kiosk-mode')).toBe(false);
  });
  it('works without storage and without the fullscreen API', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    delete document.documentElement.requestFullscreen;
    render(<KioskControls />);
    fireEvent.click(screen.getByRole('button', { name: 'Kiosk mode' }));
    expect(screen.getByRole('button', { name: 'Exit kiosk' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Fullscreen' })).toBeNull();
  });
  it('keeps app navigation out of demo mode', () => {
    render(<KioskControls showAppLink={false} />);
    expect(screen.queryByRole('link')).toBeNull();
  });
  it('keeps a configured path and fragment while rejecting unsafe link schemes', () => {
    expect(hubLink('https://example.org/hub?x=1#calendar', true, 'modern')).toBe('https://example.org/hub?x=1&kiosk=1&theme=modern#calendar');
    expect(hubLink('javascript:alert(1)', false, 'modern')).toBeNull();
    expect(hubLink('https://user:secret@example.org', false, 'modern')).toBeNull();
    expect(hubLink('not a URL', false, 'modern')).toBeNull();
  });
});
