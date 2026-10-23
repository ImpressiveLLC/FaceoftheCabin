# FaceoftheCabin touch display and kiosk

Use the **Kiosk** switch at the bottom-left of the screen.
It matches Family Hub's bottom-left switch. There is no additional top banner.
The navigation rail hides in kiosk mode; the switch remains available to turn kiosk off.
Status, alerts and location controls remain visible.

Family Hub restores its own unexpired session when returning to the same browser tab. The Cabin kiosk control stays a single switch, preserving the later banner-removal correction.

Visible scrollbars apply throughout browser presentation, not only kiosk. On
Chromium they grow from 16 to 32 pixels during interaction, then return after
three seconds. Touch panning is unchanged; the navigation rail also scrolls on
short displays. Kiosk still uses localStorage cabin.kiosk, with explicit
?kiosk=1 or ?kiosk=0 taking precedence.

Browsers can require toggling kiosk off and on to request fullscreen after navigation.
Neither kiosk nor fullscreen prevents computer sleep or supplies OS lockdown.
Native OS keyboards remain supported; an optional in-app keyboard is deferred.
