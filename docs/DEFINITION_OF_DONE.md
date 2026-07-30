# Definition of Done — FaceoftheCabin / Family Hub

> Canonical. A change is not "done" when it works on one machine — it's done
> when every item below holds. Re-run this checklist at the end of any
> substantial work session, not just when asked. Update the "Last verified"
> line each time; don't let this doc go stale itself.

**Last verified:** 2026-07-30, against commit `462b692` on `main` — deployed and confirmed live on the M920q's `family-hub` container (§8).

---

## 1. Git is reconciled — one source of truth, no drift

- [ ] Every local clone that gets worked in is either current with `origin/main`
      or its drift is understood and intentional (a feature branch, not orphaned).
- [ ] No clone has uncommitted changes that only exist on one machine.
- [ ] Stale/duplicate clones are identified and either brought current or
      explicitly retired — never left as an ambiguous second "maybe current"
      copy.

**Status:** `C:\dev\FaceoftheCabin` is the actively-maintained clone — clean,
current with `main`. `H:\My Drive\cabin-orchestration-platform-expanded\FaceoftheCabin`
is **stale** (commit `e4e3251`, far behind `main`) and had one uncommitted
edit (a `CLAUDE.md` change made in error against the wrong clone earlier
2026-07-30 — since re-applied to the real repo, see §3). Google Drive's
virtual filesystem is a known-bad environment for git (no reliable file
locking/atomic renames), which likely explains why this clone drifted and
why a second, real clone exists on `C:\dev` at all. **Open decision for the
user:** either delete the `H:\...\FaceoftheCabin` clone, or reset it to
match `origin/main` and stop editing files there. Not deleted by an agent
without confirmation — it's a destructive action outside the scope of a
routine check-in.

---

## 2. Everything is checked into git — available and current everywhere

- [ ] All working-tree changes relevant to the session are committed.
- [ ] Commit messages describe *why*, not just *what* (the diff already shows what).
- [ ] Nothing environment-specific or secret is committed (tokens, `.env`
      with real values, long-lived credentials).
- [ ] Push happens only with explicit user go-ahead — local commits are
      "done" for review purposes; `git push` is a separate, confirmed step.

**Status:** All family-hub, ontology, ROADMAP, and CLAUDE.md work through
this session is committed on `main` (`803c8b8`, `8ac96f4`, plus this
session's pending commit). **Not pushed** — push only on explicit request.

---

## 3. Docs & ontology accurately reflect current state at last check-in

- [ ] `README.md`, `ROADMAP.md`, `CLAUDE.md` don't describe removed/renamed
      things, and do describe what actually exists now.
- [ ] `docs/ontology.yaml` has an entry for every user-facing configurable
      concept, matching the real storage keys/functions in code.
- [ ] Docs written *about* a change ship in the *same* commit as the change,
      not as a follow-up someone has to remember.

**Status:** `ROADMAP.md`'s parenting-schedule row, `CLAUDE.md`'s remote-access
constraint, and `docs/ontology.yaml`'s Family Hub section are current as of
this check-in. Known pre-existing gap (not from this session, not yet
fixed): one YAML entity (`custody_status`) has an invalid `semantic:` value
(concatenated quoted strings) that breaks strict YAML parsers — flagged,
not fixed, since it predates tonight's work and needs its own review pass.

---

## 4. Local storage is optimized — no accumulating cruft

- [ ] Every `localStorage` key in use is either actively read, or has an
      explicit, time-bounded migration path that **removes the old key**
      once migrated — never kept "just in case" indefinitely.
- [ ] If the simplest correct answer is "wipe and reseed," do that instead
      of writing compatibility shims for data no one needs preserved.

**Status:** `smrekar_schedule_cfg` (the pre-versioning flat anchor) is now
deleted immediately upon migration into `smrekar_schedule_rules`, instead of
being left behind unused. Current key inventory (11 keys, all actively
read): `family_profiles`, `smrekar_rewards`, `smrekar_rewards_history`,
`smrekar_schedule_rules`, `smrekar_schedule_log`, `smrekar_holidays`,
`smrekar_custody_log`, `smrekar_hub_cfg`, `smrekar_photos_meta`,
`smrekar_chore_completion`, `smrekar_access_log`, `cabin-theme`. None
orphaned as of this check-in.

---

## 5. CI/CD pipeline is documented and current

- [ ] The pipeline definition (workflow YAML, playbook) is itself in git,
      not just described in prose.
- [ ] A README/runbook exists that lets someone with zero prior context
      recover or rebuild the pipeline from scratch.
- [ ] Docs match the actual YAML — no describing a step that isn't there.

**Status:** No CI/CD pipeline existed anywhere in either clone as of this
session's start, despite being referenced as already having "a placeholder."
See `.github/workflows/deploy-family-hub.yml` and
`ansible/README.md` added this session — self-hosted-runner pattern (see §6
for why), documented for recovery.

---

## 6. CI/CD (incl. Ansible) is actually wired to pick up changes

- [ ] A push to `main` (or a tag, per the workflow's trigger) results in the
      pipeline running without manual intervention.
- [ ] The mechanism is appropriate to the network topology — a target
      behind CGNAT/Tailscale-only needs a different approach (self-hosted
      runner, pull-based deploy) than a publicly reachable host (push-based
      SSH deploy).
- [ ] The plan for *why* this mechanism was chosen is written down, not just
      the mechanism itself.

**Status:** M920q is Tailscale-only, behind Starlink CGNAT — a GitHub-hosted
runner cannot reach it, and pushing SSH keys/secrets to GitHub for a home
server is more attack surface than needed for a free/personal-tier project.
**Chosen approach: a self-hosted GitHub Actions runner installed on the
M920q itself**, connected over Tailscale, polling GitHub — no inbound ports,
no secrets stored in GitHub. See §8 for the one manual step this requires.

---

## 7. All specs are cross-checked for accuracy

- [ ] README's product/subdomain table matches ROADMAP's.
- [ ] ROADMAP's architecture section matches what's actually in
      `docker-compose.yml` / the repo structure.
- [ ] CLAUDE.md's design constraints match current decisions (not stale
      ones from an earlier phase).
- [ ] Ontology's `source:` and `technical:` fields match the actual
      `localStorage` keys / function names in code, not what they used to be.

**Status:** Cross-checked this session — see §3. Subdomain list (`hub` /
`cabin` / `api`) is consistent between README and ROADMAP. One open
reconciliation from the previous session remains unresolved: the user
recalled `monitoring`/`qa`/`config`/`familyhub` subdomains that aren't
actually documented anywhere — still needs a human decision, not something
to silently resolve by picking one.

---

## 8. Deployment — pipeline reaches the M920q; quickstart is a reusable template

- [x] Changes reach a running container on the M920q — done **manually**
      this round (`git push` → `ssh` → `git pull` → `docker compose build/up`
      for just the `family-hub` service), not yet via the automated pipeline.
- [ ] No manual `ssh` + `git pull` needed once set up — **not yet true**;
      today's deploy was hands-on, the self-hosted runner from §6 was never
      actually registered. That's the remaining gap on this line.
- [ ] The same quickstart works as a **template** for a second host machine
      (e.g. the "home" location), parameterized rather than hardcoded to
      the cabin.
- [ ] A placeholder exists for web-hosted (non-self-hosted) deployment
      targets, even if unused today — so the template doesn't have to be
      rewritten if that need shows up later.

**Status — updated 2026-07-30, 17:25 CDT:** Tailscale was already
authenticated and connected on this machine (`ilikethelights`), and the
M920q runs **Tailscale SSH** (identity-based, no separate key management) —
reachable as user `nate` via `ssh nate@nates-little-m920q.tailb20f8b.ts.net`.
With the user's explicit go-ahead, used it for a real deploy: pushed
`main` (`e1ec494..462b692`) to `origin/main`, fast-forward pulled on
`/home/nate/FaceoftheCabin` (the clone backing the "infra" compose
project — confirmed via `docker compose ls`, distinct from the "cabin"
project that runs HA/cameras/water valve — this deploy touched neither),
rebuilt only the `family-hub` image, recreated only that container.
**Verified against the actual served response** (`curl` on the container's
real mapped port, not just container status) that the live page now
contains the new features (Holidays & Vacations, Observations, the
versioned schedule). This satisfies "changes are usable at the current
endpoint" for this round of work. Still open: the self-hosted-runner
automation from §6 was never registered, so *this* deploy was manual and
the next one will be too unless that's set up.

<details><summary>Earlier status (2026-07-30, superseded — kept for the record, don't delete history from this doc)</summary>

an agent in this environment has **no network path to the M920q** (no SSH
keys configured, no Tailscale client session, no remote-exec tool).
Nothing described in this doc has actually been *run* against the M920q —
only written, reviewed by logic tests where possible, and committed. This
turned out to be wrong — see the current status above.

</details>

---

## 9. Ontology is fully "baked" — CRUD + data definitions for every entity

- [ ] Every entity a user can Create/Read/Update/Delete through the UI has
      an ontology entry describing that capability, not just its shape.
- [ ] `transformation` fields name the actual functions (e.g. `saveReward()`,
      `deleteHoliday()`) so the doc stays traceable to code, not just prose.
- [ ] New CRUD surfaces (Rewards, Holidays, Family Profiles, Schedule Rules)
      are represented at the same level of detail as older, pre-CRUD
      entities — no second-class documentation for newer features.

**Status:** Audited this session — `reward_menu_item` and
`holiday_override` entity CRUD coverage confirmed/added (see ontology diff
this commit). `family_member_profile` already had CRUD-equivalent detail
from an earlier session.

---

## (i) Additional practices — added by Claude Code, not explicitly requested but load-bearing

- **Verification evidence travels with the change.** If a live browser/UI
  check wasn't possible (sandbox restriction, no access to the target
  device), say so explicitly rather than imply full verification happened.
  A passing Node logic-test suite is evidence of logic correctness, not of
  "I saw it render correctly."
- **No silent scope narrowing.** If a requested item can't be done (no
  remote access, ambiguous requirement, conflicting prior decision), it's
  named as blocked/open in this doc — never quietly dropped.
- **Secrets never get committed to make CI "just work."** Runner
  registration tokens, OAuth client secrets, etc. are one-time manual steps
  or environment variables on the target host — not baked into any
  committed YAML.
- **Legacy migrations clean up after themselves** (see §4) — a migration
  that runs but leaves the old data sitting around forever isn't finished,
  it's half-done.
- **This document itself is versioned like code.** Update it in the same
  commit as whatever changed its status; don't let "Last verified" drift
  from reality.
