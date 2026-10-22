# Family Hub: touch display and kiosk

The bottom-left **Kiosk** switch enters/leaves display mode. The same switch is
also in Settings > Display. The adjacent app link opens FaceoftheCabin in the same
tab, preserving only kiosk and theme. Fullscreen can require a fresh tap on the
fullscreen icon after navigation. Navigation controls reserve no top banner space.

These touch improvements apply in normal browser presentation and fullscreen too:

- Scrollbars are visible in scrollable content, including nested widgets and
  overlays. Chromium tracks grow from 16 to 32 pixels while interacting and remain
  prominent for three seconds after interaction. Finger panning stays enabled.
- Chores begin collapsed for every assignee. Tap a person to expand their tasks;
  selecting someone else collapses the previous person. Adult family members can
  be assigned chores. Long lists remain scrollable within the card.
- Resting notes align to the chores card width and show recent messages. Open the
  notepad to unroll the composer to the left while the context column stays put.
  All 50 retained notes are available in that scrollable column; long previews are
  clamped. Full history retains complete text. Narrow screens stack the panes.
- Tap **Reply** on a note to quote it in the composer. Posted replies keep an
  original-text snapshot, even if the original later falls out of the 50-note cap.
- **Sticky note / list for everyone** creates larger, bold notes with a date picker
  and **Due by / List date** selector. The date replaces sender display; the author
  remains recorded. Newlines support shopping lists and reminders.
- Shared-note failures retain the draft and show an error, not false success.

Returning from the cabin app restores an unexpired Family Hub Google session in
that same tab after server validation. Sign-out, expiry and rejected credentials
still require authentication. Tokens stay in origin-scoped sessionStorage and are
never sent in app-switching links. This is not cross-app SSO or indefinite login.

Viewport changes accommodate the native OS keyboard. An app-integrated keyboard
is deferred; no extra keyboard appears in the landing screen. This mode does not
prevent OS sleep or provide OS lockdown.

Validation: family-hub/test/run.js includes touch-refinements.js (nine assignees,
real Chromium touch gestures, notes/replies/date labels, outline matching and
mocked Google return-session/expiry checks). Physical Mint/Cinnamon keyboard and
real-account OAuth are separate hardware/account checks.
