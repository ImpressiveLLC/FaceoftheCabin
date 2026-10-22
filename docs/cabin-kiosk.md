# FaceoftheCabin touch display and kiosk

Use the **Kiosk** switch at the bottom of the existing left navigation rail.
It matches Family Hub's bottom-left switch. There is no additional top banner.
Navigation remains available, including a touch-accessible way to turn kiosk off.
Status, alerts and location controls remain visible.

**Family Hub** opens the configured location URL, or https://hub.unicornpingpong.com/,
in the same tab with only kiosk and theme parameters. Demo views omit this link.
Authentication remains separate; Family Hub now restores its own unexpired session
when returning to the same browser tab.

Visible scrollbars apply throughout browser presentation, not only kiosk. On
Chromium they grow from 16 to 32 pixels during interaction, then return after
three seconds. Touch panning is unchanged; the navigation rail also scrolls on
short displays. Kiosk still uses localStorage cabin.kiosk, with explicit
?kiosk=1 or ?kiosk=0 taking precedence.

Browsers can require a fresh tap on the fullscreen icon after navigation.
Neither kiosk nor fullscreen prevents computer sleep or supplies OS lockdown.
Native OS keyboards remain supported; an optional in-app keyboard is deferred.
