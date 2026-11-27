# W-167 shared Monitoring preferences activation

Authority: Nate's activation go relayed in [#244 comment 7225748072](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7225748072). Cowork requires independent review before an enabled build in comment 7226019772.

## What changes

The UI Dockerfile accepts and exports `VITE_MONITORING_PREFERENCES`, defaulting to `false` for standalone builds. The M920q Compose build passes `${MONITORING_PREFERENCES_ENABLED:-true}` to activate the reviewed shared preference UI through the normal deployment. An existing nonempty host override takes precedence; verify the delivered behavior rather than assuming the default took effect.

This is a build-time setting, not a runtime container toggle. Both Cabin and Home use the existing backend and their separate location preference records. Eligibility, alert evaluation, history, device enablement and Remove remain unchanged. There is no enrollment, data backfill or browser-order import. The first edit stores explicit inclusion choices for all currently eligible cards, using the current revision. Empty preferences use the established deterministic input order.

## First look and acceptance

After independent exact-head review and the normal deployment completes, verify with a signed-in authorized user at both locations:

1. Record the initial visible cards and order. Confirm the visibility/order editor is present and the preference read succeeds for the selected location.
2. Hide one ordinary non-alarm card, refresh in another browser/session, confirm the shared choice, then restore it. Preserve its original inclusion value.
3. Change an ordinary card's order, save, confirm the other browser sees it, then restore the recorded order. Do not move or hide an active alarm.
4. Confirm active alarm cards, when present, remain visible and cannot be hidden. Fixture coverage establishes the alarm-arrives-during-save case; do not trigger physical alarms to manufacture live evidence.
5. Confirm signed-out/demo/Viewer write controls remain disabled. Report unavailable session classes or absent active alarms as not exercised live, not as a live pass.
6. Report each location's result and any unavailable evidence on #244. A successful workflow or build alone is not enabled-mode acceptance.

## Rollback

On a hidden alarm, wrong order or write failure, stop acceptance and roll back immediately under Nate's instruction. Through the normal reviewed config/deployment path, change the production Compose flag default to `false` and rebuild/redeploy the UI; if an explicit host override supplied `true`, the deployment owner must also clear or set that override to `false`. Restarting the old image cannot change a baked Vite flag. Verify the new editor is absent and the previous local-order behavior returns. Retain preference records and audit history; rollback does not delete data or revert the backend schema.

This document is the activation procedure, not a claim that deployment or live acceptance has completed. No agent launches a ladder run or changes local WSL/Docker state for this task.
