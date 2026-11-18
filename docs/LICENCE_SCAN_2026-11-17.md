# Licence scan: Node, Maven and running images (2026-11-16 and 2026-11-17)

Work order: `docs/REFERENCES_AND_LICENSES.md` section 6 items 1 and 2, from #244. Not legal advice. A licence **declared in package metadata or an image label** is what the package says about itself; this scan did not read licence files, check for modified copies or check redistribution terms. Counts are from the scan runs named below; nothing here was run against production data.

## What was run

| Scan | Where | How | Date |
|---|---|---|---|
| `ui` Node packages | Code's own clean clone at `main` (Windows), `npm ci` then a read of each `node_modules/**/package.json` `license` field (offline, no service) | script, output counts only | 2026-11-17 |
| `family-hub` Node packages | same | same | 2026-11-17 |
| `backend` Maven runtime jars | same clone, `mvn dependency:list -DincludeScope=runtime`, then the `<licenses>` block of each artifact's POM in the local Maven repository (parent POM when the artifact declares none) | script, offline after resolution | 2026-11-17 |
| Running container images | the M920q, read-only: `docker image inspect` for the images behind `docker ps`, `org.opencontainers.image.*` labels only. Nothing started, stopped or changed | Nate's word in chat, 2026-11-16 | 2026-11-16 |

## `ui` (146 packages including dev dependencies)

| Licence declared | Packages |
|---|---|
| MIT | 130 |
| ISC | 5 |
| Apache-2.0 | 4 |
| BSD-2-Clause | 2 |
| BSD-3-Clause | 2 |
| MPL-2.0 | 2 |
| MIT-0 | 1 |

Non-permissive: `lightningcss` 1.33.0 and its platform binary package (`lightningcss-win32-x64-msvc`, installed because the scan ran on Windows; the Linux build installs a different platform package) are **MPL-2.0**, file-level copyleft. They come in through Vite as a build-time CSS tool; nothing from them is copied into the served bundle. If the repo ever changes those files and distributes the change, MPL-2.0 applies to those files. No GPL, AGPL, LGPL or unknown licence appeared.

The five dependencies that were `latest` are now pinned to the versions the lockfile already resolved: `@vitejs/plugin-react` 6.0.4, `vite` 8.1.5, `react` 19.2.8, `react-dom` 19.2.8, `lucide-react` 1.26.0. The register's earlier "believed MIT" and "believed ISC" for these are now backed by package metadata (see the change log below).

## `family-hub`

2 packages, both Apache-2.0. No non-permissive licence.

## `backend` (59 runtime jars, test scope excluded)

| Licence declared (as written in the POM) | Jars |
|---|---|
| Apache License 2.0, in four spellings | 48 |
| MIT (two spellings) | 3 |
| BSD-2-Clause (two spellings) | 2 |
| Public domain, per CC0 (one with BSD-2-Clause) | 2 |
| Eclipse Public License 1.0 or 2.0, alone or dual with LGPL / GPL2 with Classpath Exception | 4 |

The four EPL-family jars, none modified here, each declare more than one licence in their POM. A declared alternative is an option, not a clearance: the alternatives are listed so a person can decide which one the project relies on.

| Jar | Alternatives declared in the POM | What this scan does and does not say |
|---|---|---|
| `logback-classic`, `logback-core` | EPL-1.0 or LGPL-2.1 | Both alternatives carry obligations (EPL-1.0 is weak copyleft; LGPL-2.1 has its own terms). The project has not chosen one. Not checked against the current upstream statement at <https://logback.qos.ch/license.html>, which may differ from the historical POM of the version in use. |
| `jakarta.annotation-api` | EPL-2.0 or GPL-2.0 with the Classpath Exception | The EPL-2.0 alternative carries weak-copyleft obligations; the second alternative is the GPL with the Classpath Exception. No choice recorded. |
| `org.eclipse.paho.client.mqttv3` | EPL-2.0 or EDL-1.0 (the POM names EPL-2.0) | EDL-1.0 is a BSD-style licence; the POM text names EPL-2.0. No choice recorded. |

No GPL-only or AGPL jar appeared in the runtime list. The Spring Boot starters are Apache-2.0 as declared. All of this is metadata read from the POMs of the resolved versions (parent POM when an artifact declares none); no licence file or upstream page was read, and it is not legal clearance.

## Running images on the M920q (labels only)

| Image | Label says | Note |
|---|---|---|
| `eclipse-mosquitto:latest` | EPL-2.0 OR BSD-3-Clause | label; matches the register's belief |
| `ghcr.io/home-assistant/home-assistant:stable` | Apache-2.0 | label |
| `ghcr.io/gethomepage/homepage:latest` | GPL-3.0 | label; register already records GPL-3.0 |
| `koenkk/zigbee2mqtt:latest` | GPL-3.0 | label; register already records GPL-3.0 |
| `prom/prometheus:latest` | "Apache License 2.0" | label; Prometheus is **not in the register** (a running image) |
| `ghcr.io/open-webui/open-webui:main` | NOASSERTION | label asserts nothing; **not in the register**; needs a read of the upstream licence |
| `cloudflare/cloudflared:latest`, `grafana/grafana:latest`, `louislam/uptime-kuma:2.5.0`, `nodered/node-red:latest`, `ollama/ollama:latest` | no licence label (source or version labels only) | cannot be confirmed from the image |
| `ghcr.io/blakeblackshear/frigate:stable`, `confluentinc/cp-kafka:7.6.1`, `timescale/timescaledb:latest-pg16`, `bluenviron/mediamtx:latest`, `python:3.12-slim` | no licence label | cannot be confirmed from the image |
| `cabin-backend`, `infra-cabin-ui`, `infra-family-hub`, `blinkbridge:rewrite`, `birthday-rsvp:*` | not applicable or no label | project-built images |

Also: of the third-party images in the table above, 13 use a floating tag (12 are exactly `latest`, `stable` or `main`, plus `timescale/timescaledb:latest-pg16`); `uptime-kuma:2.5.0`, `cp-kafka:7.6.1` and `python:3.12-slim` are version-pinned. A floating tag can change licence between pulls; pinning is a separate host-side decision and not part of this PR.

## What this does not settle

- Whether the project's use of GPL-3.0 components (Homepage, Zigbee2MQTT) triggers any obligation depends on whether anything is redistributed; unchanged from the register's obligations section.
- TimescaleDB's second licence file, Blinkbridge upstream, home-llm, the trademark terms and the Llama distribution question stay open as in the register.
- Open WebUI and Prometheus need register rows once a person confirms they are intentionally running.
- No licence text was read for any package; only declared metadata and labels.
