# FaceoftheCabin kiosk display

Use **Kiosk mode** above the main toolbar for a display focused on the current
panel. It hides the navigation rail and device-count controls, retaining status,
alerts, location controls and a visible **Exit kiosk** button. Exit restores normal
navigation. Escape exits the display preference when the page receives that key.
Browser Escape may leave fullscreen first; Exit kiosk leaves both.

**Family Hub** opens the configured location's Family Hub URL, or
https://hub.unicornpingpong.com/, in the same tab. Both apps use the same control
labels and send only kiosk and theme state between apps; authentication stays
separate. Demo sessions do not show the Family Hub link.

The per-browser `cabin.kiosk` preference survives reloads. Explicit `?kiosk=1`
or `?kiosk=0` takes priority over storage. Fullscreen is requested after a tap;
use **Fullscreen** again after navigation when the browser requires it. If denied
or unavailable, kiosk works in the normal browser window. This does not prevent
computer sleep or provide OS lockdown.

QA: KioskControls.test.jsx exercises persistence, URL precedence, fullscreen
failure, unavailable storage/APIs, Escape, configured navigation, and demo link
visibility. Cabin UI PR checks run the full Vitest suite and build before merge;
the existing Deploy to host pipeline still gates deployment on both app suites.
