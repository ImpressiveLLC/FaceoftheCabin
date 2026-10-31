# Routing proposal: admin scope (workflows, access, storage, maintenance)

Corpus text merged in PR #364. The JSON map was not edited. All of this was simulated offline with the coverage map's `route.py` replica; the proposed fixture and the before/after output are in (local simulation output, not committed) (`build.py`, `proposed.json`, `before.txt`, `after.txt`).

Note: the map on main has 26 categories, not 27.

Phrasing numbers are 1-based positions in `phrasings.json` flattened in order (A1 = 1..3, ...).

## New categories (declare all of them immediately before `rules_workflows`)

Ties go to declaration order, so they must precede `rules_workflows`. None of them includes the bare keyword `workflow`; `rules_workflows` keeps it, so "What can a workflow do?" still reaches K05.

| Category | Keywords | Sections (file#anchor, audience) | Answers |
|---|---|---|---|
| `workflow_schedule` | schedule, scheduled, every night, every morning, every day, certain time, time of day, at a certain time, on a timer | workflows.md#can-a-workflow-run-on-a-schedule-or-at-a-time-of-day (household) | 53, 54 |
| `workflow_run_history` | fire now, activate, deactivate, turn a workflow on, mark seen, reset, undo, workflow history, history of a workflow, workflow ran, does nothing, doesn't do anything, recent firing, workflow firing, run a workflow, workflow by hand, workflow on or off | workflows.md#turn-a-workflow-on-or-off-run-it-by-hand-and-see-when-it-ran (household) | 30, 31, 36, 37, 38, 39, 40, 49, 50 |
| `workflow_settings` | cooldown, stays fired, fire again, condition clears, broken, degraded, replacement device, different device, over and over | workflows.md#workflow-settings-after-it-fires-cooldown-and-broken-targets (administrator) | 41, 42, 43, 44, 45, 46 |
| `workflow_create` | create a workflow, create a new workflow, new workflow, make a workflow, save draft, not available yet, pick that trigger, can something happen, when someone arrives, shut off the water, start a workflow | workflows.md#create-a-workflow (administrator), workflows.md#what-a-workflow-is-and-where-it-runs (household) | 28, 29, 32, 33, 47, 48 |
| `access_sharing` | guest access, guest link, share link, shared, read-only access, revoke, demo link, adjuster, contractor, access link, view activity | access-and-storage.md#share-read-only-access-with-a-link-guest-access (administrator), access-and-storage.md#give-someone-a-demo-link (administrator) | 122, 123, 124 |
| `managed_users` | managed user, invite, google account, household member, sign-in link, magic link, add someone, add a user | access-and-storage.md#add-someone-without-a-google-account-managed-users (administrator), access-and-storage.md#who-can-see-and-change-what (household) | 125, 126 |
| `who_can_change` | who is allowed, who can, allowed to, permission, can't create, cannot create, read-only account, viewer | access-and-storage.md#who-can-see-and-change-what (household) | 52 (51 stays on `rules_workflows`, whose K05 "Who can do what" and the create section both answer it) |
| `storage_disk` | disk, disk space, almost full, storage warning, free up, trim, trim old backups, old backups, storage | access-and-storage.md#disk-space-warnings (household), access-and-storage.md#trim-old-backups (administrator) | 112, 113, 114, 115 |

Budget check (6000 characters shared): largest new category is `storage_disk` at about 4,300; `workflow_create` about 3,700.

## Changes to existing categories

| Category | Change | Answers |
|---|---|---|
| `rules_workflows` | Do **not** prepend workflows.md#what-a-workflow-is-and-where-it-runs: it takes the category from about 4,900 to about 6,500 characters, over the 6000 limit (`ContextMapWiringTest`; KNOWN_OVER_BUDGET is empty). Keep the category as is, and give that section its own small category `workflow_basics` (next row), declared before `rules_workflows` | 18, 19, 26, 27, 34, 35, 51 (answered only if `workflow_basics` is added and measured) |
| `workflow_basics` (new, about 1,630 characters, not simulated) | workflows.md#what-a-workflow-is-and-where-it-runs (household). Keywords to try: what is a workflow, what can a workflow do, workflow turn on, where does a workflow run, home assistant automation, automations actually run, run by the app, by home assistant. Declare before `rules_workflows`; do not use bare `workflow`. Measure against phrasings 18, 19, 26, 27, 34, 35, 51 and the rest of the 150 | same |
| `deployment_source` | Remove `workflow` and `manual` (they pull workflow questions toward deployment; "workflow" here means GitHub workflow). Add: github workflow, deployed, update, roll back, rollback. maintenance.md#deployment-source-correction is 5,889 characters after the review fix (limit 6,000) | 131, 132 |
| `backup_restore_recipes` | Add: restore from a backup | 116 (already routed), 117 (was misrouted to `persistence_recovery`) |
| `self_healing_recovery` | Add: how do i restart, restart a service, restart one service, blink bridge. Do not add bare `restart`: it misroutes "after the restart" (phrasing 67) | 118, 119 |
| `health_verification` | Add: everything working, everything is working, is it up | 120, 121 |
| `configuration_binding` | Add: home assistant devices, home assistant token, not updating, stopped updating, token valid. Do not add bare `home assistant`: it captures phrasing 19 | 129, 130 (130 was going to `credential_redaction`) |
| `health_triage` | Append maintenance.md#close-the-incident (administrator). Add: incident, postmortem, root cause | none in the 150; routability only |

Simulated result over all 150 phrasings: every phrasing 26 to 54 and 112 to 132 in my scope routes to a section that answers it, except 127/128 (E8, unchanged and already correct by word overlap and `credential_redaction`). No phrasing outside the scope changed category. The simulation assumed the what-a-workflow section was prepended to `rules_workflows` (21 (A10) and 72 (C8) got it); with the prepend dropped for budget, 26, 27, 34 and 35 depend on the new `workflow_basics` category, which has not been simulated, and household callers get only the alerts S8 passage there until it is measured. 141 (E15) on `health_triage` gets close-the-incident. Earlier drafts misrouted phrasings 19, 58, 67 and 98 through bare `home assistant`, `by hand` and `restart`; those keywords were dropped.

## New and fixed sections

| File | Heading | Anchor | Audience proposed | Answers |
|---|---|---|---|---|
| workflows.md | What a workflow is and where it runs | what-a-workflow-is-and-where-it-runs | household | 18, 19, 26, 27, 34, 35 |
| workflows.md | Create a workflow | create-a-workflow | administrator (see note) | 28, 29, 32, 33, 47, 48, 51 |
| workflows.md | Turn a workflow on or off, run it by hand, and see when it ran | turn-a-workflow-on-or-off-run-it-by-hand-and-see-when-it-ran | household | 30, 31, 36 to 40, 49, 50 |
| workflows.md | Workflow settings: After it fires, cooldown and broken targets | workflow-settings-after-it-fires-cooldown-and-broken-targets | administrator (see note) | 41 to 46 |
| workflows.md | Can a workflow run on a schedule or at a time of day | can-a-workflow-run-on-a-schedule-or-at-a-time-of-day | household | 53, 54 |
| access-and-storage.md | Who can see and change what | who-can-see-and-change-what | household | 51, 52 |
| access-and-storage.md | Share read-only access with a link (Guest Access) | share-read-only-access-with-a-link-guest-access | administrator | 122, 123 |
| access-and-storage.md | Give someone a demo link | give-someone-a-demo-link | administrator | 123, 124 |
| access-and-storage.md | Add someone without a Google account (Managed Users) | add-someone-without-a-google-account-managed-users | administrator | 125, 126 |
| access-and-storage.md | Disk space warnings | disk-space-warnings | household (it tells a household member what the warning means and that Trim is an administrator task) | 112, 113 |
| access-and-storage.md | Trim old backups | trim-old-backups | administrator | 114, 115 |
| maintenance.md (fixed: short answer added) | What detects and what recovers | what-detects-and-what-recovers | administrator (unchanged) | 118, 119 |
| maintenance.md (fixed: short answer added) | Deployment source correction | deployment-source-correction | administrator (unchanged) | 131, 132 |
| maintenance.md (fixed: short answer added) | Backup and restore recipes per store (K10, verified from source only) | backup-and-restore-recipes-per-store-k10-verified-from-source-only | administrator (unchanged) | 116, 117 |

Audience note: the brief says household members cannot change rules or devices. The code on main does not match that for workflows: `RulesController` writes need only a signed-in session with write access, and any Google sign-in that is not on the administrator list is an adult household member, as is a managed "Household member (read/write)". So the create and settings sections are proposed as administrator-only by policy, not because the server enforces it. If Cowork wants Ask to match the server, they can be household.

## Rows in scope that need no new text (keywords only, or another writer's scope)

- E5 (120, 121): `service-health-at-a-glance` already answers; keywords above.
- E8 (127, 128): `credentials-and-access` already answers; no change.
- E9 (129, 130): `ha-token-validity-k09-slice-2` already answers; keywords above.
- E11, E12 (133 to 136): the W-88 and camera sections are the devices writer's scope; not touched here.
- B7 (38 to 40, Reset, Mark seen, no undo) is also in the alerts writer's scope. workflows.md states it once in the History section; if the alerts branch adds its own passage, keep one and link the other.

## Unmapped sections: should they be routable?

| Section | Decision | How |
|---|---|---|
| maintenance.md#close-the-incident | Yes, administrator | `health_triage` (above) |
| maintenance.md#new-hardware-pending-install-2026-10-16 | Yes, household (no secrets; plain status of ordered hardware) | Append to `environmental_nodes` only if the 6,000 budget allows (that category already has five sections); otherwise a small category with keywords: new hardware, pending install, not installed yet, not paired |
| configuration.md#explicit-source-disagreement | Yes, administrator | Append to `config_build_time_binding` (already has `host-config`); add keywords: webui_auth, open webui login, old config, cached config |
| configuration.md#new-device-classes-not-yet-configurable-2026-10-16 | Yes, household | Same small category as the pending-hardware section, or `environmental_nodes`; add keywords: esphome, configure the dehumidifier, configure the fan |
| configuration.md#remaining-configuration-work | No | It describes documentation completeness, not anything a person would ask the app |
