# Family Hub kiosk display

In Settings > Display, choose **Kiosk mode** for a wall display. The display
requests browser fullscreen when supported and retains the preference on this
browser. A visible **Exit kiosk** button always restores the normal display;
Escape also exits the display preference when the page receives that key.
Browser Escape can leave fullscreen first; use Exit kiosk to leave both.

The kiosk toolbar and Settings offer **FaceoftheCabin** in the same tab. Links
carry only `kiosk=1` (or `0`) and the selected theme. Each app still applies its
own authentication. This is a display mode, not an OS lockdown or a sign-in bypass.
It does not keep a sleeping computer awake.

An explicit `?kiosk=1` or `?kiosk=0` overrides the saved preference. Reloading
retains the layout, but browsers require a fresh tap on **Fullscreen** after
navigation. If fullscreen is unavailable or denied, the windowed layout remains
usable. Family Hub uses localStorage `familyHub.kiosk`; blocked storage does not
prevent the controls from working.

QA: Family Hub's existing Playwright suite checks persistence, explicit URL
overrides, fullscreen rejection, exit controls, and cross-app display parameters.
The Family Hub PR checks workflow runs the same Docker-based suite before merge;
the existing Deploy to host workflow still validates both apps before deployment.
