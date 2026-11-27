# W-167 C1 PR 1: Monitoring preferences contract

Assigned by [Cowork 7222677673](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7222677673), with the split refined in [7223110906](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7223110906). Backend only. No UI consumer, production backfill caller, scheduled task, host operation or B2 adoption is added.

## Eligibility boundary

Preferences can only hide or order cards the existing client projection already produces. They never establish eligibility, enable a source or create a tile. The pure shadow contract ignores unknown/stale/ineligible preference keys, reports missing specifications and duplicate projected identities, and preserves the input order for cards without a preference. Its input is the existing compatibility projection, not an enabled-device registry scan. The client integration and golden parity proof belong to PR 2; the Java shadow helper is a contract reference, not a replacement renderer.

The backend validates canonical device identity and explicit location only. A successful preference write is not proof of renderer eligibility. Disabled electrical parents remain valid identities for groups supplied by eligible children. Missing/ambiguous location is rejected, never assigned to Cabin. No lifecycle normalization occurs.

## API

`GET /api/devices/monitoring-preferences?location=cabin|home` returns only `location`, `revision`, and ordered `cards` containing `deviceId` and `included`. Missing state returns revision zero and an empty list without inserting anything. No observation values, actor identities or audit timestamps appear. Existing public device-read authorization remains; demo reads are explicitly classified as allowed.

`PATCH` on the same path accepts `expectedRevision` and `cards`. The verified request identity supplies the audit actor. Each card must explicitly supply `included`; arbitrary fields are rejected. All incoming IDs must exist at the exact supplied location, without a default location. An empty or partial list never erases other stored preferences: submitted cards form the preferred prefix, omitted existing cards retain their inclusion and relative order at the tail. This allows an obsolete stored key to remain ignored without preventing edits to current cards. Hide is an explicit `included:false` choice, not omission.

Writes require sign-in. Existing interceptor/filter rules deny demo, guest and read-only managed sessions before the handler. A handler-level verified-actor check also fails closed. Stale revision returns 409 and requires refetch/retry. Invalid identity, location, duplicate key or payload returns 400. Writes do not assert tile eligibility. Profile display overrides, their existing Remove action and DELETE endpoint remain unchanged.

## Persistence and migration

Three separate tables hold per-location revision, ordered per-card inclusion with an explicit-choice marker, and actor/time/operation plus resulting preference snapshot audit. Revision locking, preference changes and audit insertion use one transaction. Audit failure rolls back the change. A single joined read statement returns a consistent revision/card snapshot.

The backfill service accepts trusted, already-verified compatibility IDs in deterministic server-input order. It only inserts missing rows, never changes existing explicit choices or their order; repeated identical backfill changes neither revision nor audit count. It has no endpoint, startup caller or scheduled caller. Production backfill remains inactive until PR 2 verifies the projection integration. No untrusted browser list supplies eligibility authority. No browser-order import in v1. Alarm pinning stays transient in the existing UI; callers must never save transient alarm positions as preferred order.

PR 2 will flag-guard UI read-through and prove before/after parity. PR 3 will add distinct Show/Hide controls and signed-in editing, preserving safety alert evaluation and confirmation wording. Neither is implemented here. Rolling back this backend restores the prior API availability; the additive preference and audit tables can remain. No telemetry, registry, enrollment or alert data is modified.

## Verification boundary

Unit/API tests cover the hide/order-only invariant, unknown-key ignoring, shadow differences, anonymous and guest denial before service access, verified actor attribution, missing revision/inclusion and locked-field rejection, and exact public response shape. Demo tests cover all write methods before the controller. Existing write, demo-route, CORS and constructor guards apply.

Real-Postgres tests cover rollback on audit failure, service restart persistence, simultaneous revision conflicts, idempotent backfill preserving explicit choices, partial updates retaining stale/hidden preferences, and invalid writes leaving persistence untouched. These tests require an authorized database runner; compilation is not execution. Full backend suite and those database tests remain merge gates. Deployment health, when authorized and performed after review, is only backend runtime proof: with no UI consumer it is not end-to-end feature verification.
