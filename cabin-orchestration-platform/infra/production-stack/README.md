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
(`deploy-cabin-backend.yml`, `deploy-family-hub.yml`). This directory's
stack does not yet have its own deploy workflow; see
`docs/REPLICATION.md` §10 and the project plan this was imported under
for the phased rollout (health checks, Uptime Kuma config-as-code, a
cross-container smoke test, then a real gated deploy workflow).

`docker-compose.yml` and `frigate/config.yml` here are byte-for-byte
copies of the live files on the M920q as of the import (verified via
`sha256sum`) — `frigate/config.yml` specifically came from
`/storage/services/frigate/config.yml`, the path the running `frigate`
container actually bind-mounts, **not** the stale, orphaned
`frigate/config.yml` that had been sitting unused next to the old
compose file at the untracked location. `.env` stays on the host only
(never committed) — see `.env.production-stack.example` for the keys it
needs.

**Known pre-existing drift found during this import, not yet cleaned
up:** the live host's `.env` has several duplicate `CAMERA_PASSWORD=`
lines (later ones silently win) and one malformed line with no `=` —
worth a manual cleanup pass on the host, out of scope for this import
itself. Several `.bak-*` snapshots of `frigate/config.yml` and the old
compose file remain on the host; safe to delete once this import has
lived under git history for a while (git is the backup from here on),
not done as part of this import.
