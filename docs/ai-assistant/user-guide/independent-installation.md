# Recreating an independent instance

Audience: installer/operator. Status: source-reviewed planning and prerequisite supplement; **not yet rehearsed on a clean host**. Use with [REPLICATION.md](../../REPLICATION.md), not as a claim of a finished automatic installer. A separate instance has its own resources and ownership; adding another location to the existing cabin is a different operation.

## Minimum inputs

Ask only for choices required by selected capabilities, retain confirmed non-secret answers and show assumptions for correction.

| Input | When needed | What should not be collected in chat |
|---|---|---|
| Selected capabilities and household needs | Always, to choose services and acceptance tests | Unnecessary personal history or all optional integrations in advance |
| Authorized target host and operator access | Before inspecting/provisioning a new machine | SSH private keys or login tokens |
| Independent repository/instance identity | Before setting deployment paths and runner target | Existing cabin credentials, personal seed data or its deployed paths copied as defaults |
| Access model/domain and account ownership | Before exposing services and configuring auth | DNS/API tokens, OAuth client secrets or vault passwords |
| Integration/device choices | Only for enabled features | Raw provider credentials; use secure setup and vault pointers |
| Physical/site information | Only when the feature requires it, such as selected zone-based presence | Precise location data committed into the shared corpus |

The existing replication guide distinguishes own repository, domain, accounts and host, and calls out conditional GPS presence input. Its provider pricing/setup claims need current provider verification before purchase/account setup; this supplement makes no current pricing promise. Source: [REPLICATION sections 1–4](../../REPLICATION.md).

## Establish the actual installation plan

1. **Inspect the approved target.** Record OS/architecture, Docker/Compose availability, storage, occupied ports, existing HA/MQTT services, intended remote access and selected hardware. Do not mutate an unidentified host.
2. **Choose stack ownership.** Decide which services are new and which already exist. Read base and overlay together; the same declared service may be disabled by profile. Map service names, network ownership and persistence before launch.
3. **Prepare independent source/configuration.** Use your own reviewed repository, paths, domains, accounts and user seeds. Review hardcoded source-instance assumptions in workflows/Ansible, not just branding strings.
4. **Provision host prerequisites.** The development bootstrap and actual host provisioning differ; see the verified limitations below. External account creation/consent/DNS remain explicit secure steps.
5. **Provision independent secrets.** Follow the reviewed Ansible/Vaultwarden procedures with new-instance values. Validate by safe status/presence checks, never by copying the source instance's secret store into the assistant.
6. **Prepare selected services and persistent state.** Ensure required bind-mount content, networks and device access exist. Select only the services actually needed, with reviewed overrides; do not blindly start every declaration.
7. **Build and deploy to the authorized target.** Follow the reviewed workflow/manual path for that instance. Browser build settings and runtime settings are different. Record source commit, built artifacts and resulting runtime identity.
8. **Complete integration setup.** Sign in, enroll supported users, commission selected devices and verify data flow. A candidate record or green container is not a successful integration.
9. **Verify real user journeys and recovery.** Use the acceptance record below, then leave an operator runbook, backup/restore procedure and remaining manual-step list.

Sources: [replication setup/acceptance](../../REPLICATION.md), [host provisioning role](../../../ansible/roles/cabin_host/tasks/main.yml), [base Compose](../../../cabin-orchestration-platform/infra/docker-compose.yml), [M920q overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml), [production stack](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml), [deployment workflows](../../../.github/workflows).

## Verified clean-host limitations

| Source observation | Consequence for recreation |
|---|---|
| [bootstrap-ubuntu.sh](../../../cabin-orchestration-platform/scripts/bootstrap-ubuntu.sh) installs development packages but does not install Docker/Compose or deploy the platform. | Running it is not a complete platform install. |
| [cabin_host role](../../../ansible/roles/cabin_host/tasks/main.yml) installs Docker/Compose, updates a checkout and registers a GitHub runner; [Ansible guide](../../../ansible/README.md) says DNS/tunnel/OAuth are external steps. | Host preparation alone does not complete application or account setup. Audit the current runner/platform prerequisites when executing. |
| The M920q overlay disables duplicate base services and joins an external `cabin_default` network. | It assumes an existing support stack/network. A blank host needs an explicit reviewed arrangement first. |
| The [production stack](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml) uses host bind mounts and a locally built Blink bridge. | A cloned compose file is insufficient unless required host content/build context is supplied or the capability omitted through a supported plan. |
| [Local Compose override](../../../cabin-orchestration-platform/infra/docker-compose.local.yml) only disables selected Linux-specific services. | It is not a self-contained replacement for the app-service definitions added by the M920q overlay. |
| The Ollama compose comment specifies a separate initial model pull. | Starting an Ollama container does not prove the required model exists. Model installation/digest verification is a setup step. |
| Existing environment/browser configuration includes instance-specific defaults. | An independent installation needs explicit approved substitutions and a browser check against its own endpoints. |

These are documented gaps to close in installation engineering, not reasons to delay the rest of the corpus. No reusable standalone overlay or provisioning executor is introduced by this documentation delivery.

## Acceptance record

For every selected capability retain `step_id`, owner, required input, preconditions, status (`not started`, `blocked`, `running`, `verified`), evidence, source commit and next action. A useful installer can resume without rediscovering approved inputs or blindly repeating external operations.

Verify at minimum:

- The new instance uses its own repository/accounts/resources and does not call the original cabin unintentionally.
- Intended pages load and authentication works in a real browser with the intended origins.
- A note/profile/chore change persists across two authorized browser sessions when those features are enabled.
- Selected integrations deliver actual expected observations; unselected integrations are not silently required.
- Ask identifies the right corpus/model version and answers/refuses the applicable eval questions with sources.
- Health checks prove their named functions, and at least one controlled failure has a useful recovery path in isolation.
- A documented backup restores required state on an isolated replacement target before claiming recovery capability.

Use [existing acceptance tests](../../REPLICATION.md#5-new-instance-acceptance-test), [maintenance checks](maintenance.md) and [corpus evals](../rag/eval-questions.md). Do not introduce bad credentials, fake sensor events or destructive tests into the live cabin to prove a new installation path. At this revision every clean-room acceptance result is **NOT RUN**.
