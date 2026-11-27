# W-167 C1 PR 3: shared visibility and order editing

Implements [Cowork's confirmed contract and corrections](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7225051269). Uses the existing default-off `VITE_MONITORING_PREFERENCES` boundary. This PR does not activate the flag, enroll sources, introduce backfill or adopt B2. Nate's go after review governs rollout.

## User behavior

Each location has a separate expandable **Monitoring visibility and order** control in shared mode. The list comes from the existing rendered/projected cards before preference filtering; it includes hidden cards but excludes unlocated or unsupported candidates. **Show on Monitoring** and **Hide from Monitoring** are separate from the existing Add, Change and Remove controls and their profile display settings. A sentence explains that Hide does not delete the device, its history or its display settings.

Hiding a smoke, CO, leak, valve, lock, temperature or thermostat card asks for confirmation that alerts stay active, the device stays enabled and history is kept. These are existing device-type identifiers, not a new eligibility policy. Every temperature card is conservatively covered rather than guessing which one provides freeze protection. Types without an existing tile remain ineligible; the confirmation list does not create tiles.

Hide is disabled for rendered ALARM/CRITICAL tiles. If an alarm arrives during a save, the existing render rule keeps the tile visible and its editor status says the active alarm stays visible. A successful request says only **Preference saved**, never falsely announces that an active alarm was hidden.

Signed-out, demo, guest and managed Viewer sessions have disabled write controls with a reason. Server authorization is still authoritative. Read-only refresh remains available. Flag-off renders none of the new controls and preserves local drag behavior.

## Write and order contract

Writes use the existing authenticated fetcher and PATCH API for the explicit location, with the current revision and an explicit inclusion Boolean for every submitted card. No client actor, device metadata, source commands, display-setting deletion or enrollment writes are sent. Pending state prevents duplicate submission; state changes are accepted only after a successful validated response. A timeout means the outcome is unconfirmed and asks for refresh, since the server may already have committed.

A 409 triggers a fresh read and asks the user to review and retry. No stale write is automatically replayed. A 401/403 or network failure does not claim success or replace the prior preference snapshot. Late responses after a location/fetcher change are ignored. The explicit refresh control lets another browser observe saved choices without background writes or a new polling loop.

Order uses Up/Down and an explicit **Save order** action. Only ordinary visible cards move. Hidden and alarm cards retain their positions in the underlying preference order; alarm pinning is a display operation and is never serialized as base order. An alarm or membership change invalidates an unsaved order draft. Submitted hidden cards carry their existing `included:false`; omitted obsolete keys remain stored under the backend's existing partial-update semantics.

## Verification and release boundary

Tests cover disabled session classes, safety confirmations and cancellation, explicit request bodies, duplicate submission, denial/network outcomes, conflict/refetch between two readers without replay, hidden/alarm base-order slots, stale save responses and an alarm arriving during Hide. Existing type parity, masking, history, projection and flag-off tests remain in the full UI suite.

This completes the three implementation slices when independently reviewed and deployed, but does not establish live end-to-end rollout. The build flag remains off. Activation, an enabled-mode live acceptance exercise and any future trusted backfill producer remain separate from dormant code delivery. Existing alert evaluation, telemetry, Remove/DELETE and all B2/source/host/training holds are unchanged.
