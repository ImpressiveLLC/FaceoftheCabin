# Production stack (imported)

This directory is a verbatim import of the compose stack that has always
run the M920q's real mosquitto/Frigate/Zigbee2MQTT/Home Assistant/Node-RED
etc. — previously a completely separate, git-untracked directory on the
host at `/storage/containers/compose/cabin/`, hand-edited directly with no
review or rollback beyond manual `.bak-*` copies.

Distinct from `cabin-orchestration-platform/infra/docker-compose.yml` +
`docker-compose.m920q.yml`, which define this repo's *own* services
(cabin-backend, cabin-discovery, cabin-ui, family-hub, cabin-postgres,
cabin-kafka, cabin-grafana) — those already deploy via tested/gated CI
(`deploy-cabin-backend.yml`, `deploy-family-hub.yml`). This directory deploys
through `.github/workflows/deploy-production-stack.yml`; see
`docs/REPLICATION.md` §10 for the monitoring model around it.

The workflow validates both compose layers before it changes the host. For a
production-stack config change, it preserves the current compose file, live
Frigate config, and exact running image IDs as the last-known-good set, applies
the reviewed files, and waits for every container plus every available Docker
healthcheck. It then publishes a unique, non-retained Zigbee2MQTT-shaped device
list and proves that `cabin-backend` exposes the synthetic device through
`/api/devices`; it also requires `cabin/camera/available` to report `online`.
The memory-only synthetic candidate is deleted after the check. Any failed
post-deploy gate restores the last-known-good config and images and still leaves
the Actions run red for review. A `docker-compose.m920q.yml`-only change runs the
validation and smoke checks without redeploying this separate production stack.

`docker-compose.yml` and `frigate/config.yml` here are byte-for-byte
copies of the live files on the M920q as of the import (verified via
`sha256sum`) — `frigate/config.yml` specifically came from
`/storage/services/frigate/config.yml`, the path the running `frigate`
container actually bind-mounts, **not** the stale, orphaned
`frigate/config.yml` that had been sitting unused next to the old
compose file at the untracked location. `.env` stays on the host only
(never committed) — see `.env.production-stack.example` for the keys it
needs.

**Security note (resolved before this import's first merge attempt):**
the original copy of `frigate/config.yml` pulled from the live host had
the Reolink camera's admin password hardcoded directly in two RTSP URLs
(plus a comment) instead of using the `{CAMERA_PASSWORD}` substitution
the file's own header already documented. That version was pushed
briefly to a PR on the public fork before being caught by a follow-up
grep, and was contained immediately (PR closed, branch deleted) and
fixed on both the live host and here — both now use the substitution
pattern, verified byte-identical via `sha256sum`. The exposed password
was rotated on the camera itself as a precaution. `.env`'s duplicate/
placeholder `CAMERA_PASSWORD` lines and one malformed line (no `=`) were
also cleaned up to a single entry per key while fixing this. Several
`.bak-*` snapshots of `frigate/config.yml` and the old compose file
still remain on the host; safe to delete now that this import has real
git history behind it.
