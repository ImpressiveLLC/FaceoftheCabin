# W-167 C1 PR 2: guarded Monitoring reads

Implements [Cowork's PR 2 disposition](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7224075017), following #621's backend deployment at `8649f4ea`. This slice reads preferences only. It adds no editing, backfill caller, device enrollment or B2 adoption.

## Behavior and rollback

`VITE_MONITORING_PREFERENCES=true` opts a build into shared preference reads. The default is off; deployment configuration is not changed by this PR. A build without the flag retains the existing browser order and drag behavior and makes no preference requests. Rollback is a build with the flag unset/false, or rollback to the prior UI artifact. Existing local order remains available; it is never imported into server preferences.

With the flag enabled, each displayed location fetches its own backend's preferences. The existing electrical projection and Zigbee duplicate collapse remain unchanged. Preferences apply only after tile eligibility/rendering, so they cannot introduce an unsupported source row or resurrect a collapsed child. Empty preferences preserve the projected card set and deterministic server-input order. Unknown preference IDs are ignored. Unlocated cards keep their existing presentation; neither location's stored preferences may implicitly assign or hide them.

A tile whose rendered state is ALARM or CRITICAL always appears and is pinned ahead of ordinary tiles, even when its preference says hidden. This includes a water-leak renderer deriving ALARM from a wet reading while the raw device state is ONLINE. A hidden card becoming an alarm reappears on the device update without waiting for a preference refetch. This is a presentation floor; alert evaluation and notification code are unchanged.

Preferences never filter the device collection supplied to history. Values, source identity, group provenance, occupancy masking, lifecycle checks and stale rendering stay with the existing renderers. A disabled electrical parent may still show eligible child readings without activation.

Failed, malformed or wrong-location responses fall back to that browser's existing view with a visible status. Requests have a ten-second abort deadline; superseded/unmounted requests cannot overwrite the current snapshot. Flag-on order view is read-only with plain explanatory wording until PR 3 adds authenticated server edits. Flag-off drag stays unchanged. No GET, render or effect writes preferences.

## Backfill decision

Production backfill stays inactive in PR 2. Empty preferences already represent the exact projected set, so eager default rows are unnecessary. This supersedes the earlier requirement to activate backfill during PR 2. A trusted producer for durable default rows, if ever needed, requires a separate ruling. No browser seed list becomes eligibility authority; no Java copy of the renderer is introduced.

## Verification

Tests compare flag-off and empty-preference rendering across all 24 existing device types, asserting the 15 direct renderer cases and nine absent cases. Additional cases cover disabled-parent/child grouping, hiding without child resurrection, unknown keys, separate location reads, unlocated rows, history input preservation, stale-response rejection, malformed-response fallback, read-only shared ordering and flag-off drag. Hidden cards becoming ALARM/CRITICAL and renderer-derived leak alarms are explicit regression cases.

Full UI tests and production build are required before READY. Backend source and schema are unchanged. Deployment with the default-off flag proves delivery of a dormant read-through capability, not a live shared-editing workflow. PR 3 remains responsible for distinct Show/Hide controls, signed-in revision-checked writes and safety-removal explanations. Existing Remove and its profile-display DELETE endpoint remain untouched.
