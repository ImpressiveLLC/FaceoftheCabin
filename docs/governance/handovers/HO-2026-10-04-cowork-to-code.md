# Handover HO-2026-10-04: Cowork to Code

Answers [HO-2026-10-04-code-to-cowork.md](HO-2026-10-04-code-to-cowork.md) (on [#120](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/120)) and the twelve questions Code put to Nate on 2026-10-04. Reviewed against `origin/main` @ `8485b7c` and the heads of PRs #97 to #126 as fetched 2026-10-04 08:00 UTC.

Two kinds of answer below:

- **Cowork ruling**: binding now. Code acts on it.
- **Nate decides**: Cowork's recommendation is stated. Code waits for Nate's answer, recorded on the named PR, before acting. If Nate replies "accept Cowork's recommendations", every recommendation in this file becomes his decision.

Code's 2026-10-04 rulings under Nate's delegation (DL-06 ratified deviation, DL-08 and DL-11 closed, DL-09 defect, W-4 closed) are **accepted** by Cowork without amendment.

---

## 1. ID ruling

Provisional ids are converted on acceptance. Next free number in git, checked across every branch: W-39.

| Provisional | Becomes | Provisional | Becomes |
|---|---|---|---|
| W.1 | **W-39** | W.4 | **W-42** |
| W.2 | **W-40** | W.5 | **W-43** |
| W.3 | **W-41** | W.6 | **W-44** |

`DEP.1` to `DEP.4` stay provisional: they belong to the Ask list (`docs/ai-assistant/wsjf-backlog.md`), whose owner is Codex. The `W.n` and `DEP.n` sub-id form stays legal for triage only; nothing is scored or built under it. Re-check W-39 is still free at commit time. If not, take the next free number and log a DL entry.

## 2. Scores (Cowork)

CoD = (Safety x2) + Usability + Extensibility + Time. Size is Fibonacci. The Safety figure shown is already doubled.

| ID | What | CoD components | CoD | Size | WSJF | Status |
|---|---|---|---|---|---|---|
| W-23 | Cabin zone publisher, agnostic phone check | 8+6+4+9 | 27 | 3 | **9.0** | in review (#122) |
| W-33 | Siren gate staleness rule | 16+6+2+10 | 34 | 2 | **17.0** | in review (#118) |
| W-34 | Hub presence staleness cue | 8+7+3+6 | 24 | 2 | **12.0** | in review (#121) |
| W-35 | Arm-while-occupied banner | 8+6+2+6 | 22 | 2 | **11.0** | in review (#124) |
| W-36 | Presence without the Companion app (ideation) | 8+7+6+5 | 26 | 3 | **8.7** | proposed |
| W-37 | WiFi presence without per-phone settings (ideation) | 4+5+4+3 | 16 | 3 | **5.3** | proposed; after W-36 findings |
| W-38 | Enforce location roles (D24) | 6+3+7+3 | 19 | 5 | **3.8** | proposed; after D24 ratified |
| W-39 (W.1) | Camera config under git, detection on | 16+5+6+9 | 36 | 3 | **12.0** | held on Nate (credential) |
| W-40 (W.2) | M920q egress path | 2+2+2+2 | 8 | 1 | **8.0** | ruled, see §3 Q5 |
| W-41 (W.3) | UI and tests name the new camera key | 0+5+4+4 | 13 | 2 | **6.5** | proposed; after W-39 |
| W-42 (W.4) | Scope and redact the Tier 1 device list | 14+4+5+10 | 33 | 3 | **11.0** | proposed; **gates the UC-1 link** |
| W-43 (W.5) | Anonymous device-read redaction policy | 6+3+3+2 | 14 | 2 | **7.0** | held on W-44 |
| W-44 (W.6) | Multi-user kiosk roles: use-case pass only | 4+6+8+2 | 20 | 2 | **10.0** | proposed; the build is re-scored after the UC exists |

**UC-1 release gate, amended.** Sign-offs are append-only, so this is recorded here and in the next sign-off rather than in SO-2026-09-23-r1: the agent link waits on **W-42** as well as W-3. W-1, W-2, W-2b and W-4 are done.

## 3. Rulings and recommendations on the twelve questions

### Frigate motion fix

**Q1. Which camera password is real?** *Nate decides; recommendation follows.*
The working credential is canonical. The camera streams at about 5 fps with the literal value in the live RTSP URLs, so that value is the real one by evidence. Nobody types it into a chat or a PR. Two acceptable routes:
- (a) Nate updates `vault_camera_password` himself with `ansible-vault edit` and the live `.env`, then tells Code "aligned".
- (b) Nate approves Code, on the PR, to copy the live value into the vault and `.env` on the M920q without displaying it, by the same length-and-equality method Code used to compare them. The vault commit follows the credential lifecycle in #109.

Either way: rotate afterwards if the value was ever in a hand-edited file that left the host. Code confirms with `ffprobe` from the Frigate container using the placeholder-rendered URL.

**Q2. Draft the config-sync PR now?** *Cowork ruling: yes, as a draft.*
Branch from fresh `main`. Contents:
- Camera key **`cabin_outside_reolink`**, settled here so W-41 can proceed. It is the key recordings have been filed under since 2026-09-30, and renaming it back would split the camera's history.
- Address `192.168.1.121` on `eno2`.
- `{FRIGATE_RTSP_PASSWORD}` placeholder in both URLs.
- `detect.enabled: true`.
- The classes from Q3.
- Keep `home_aldrich_front: enabled: false` only if Nate confirms it was intended. Otherwise restore it and say so on the PR.
- The Person On Foot `animal` and `car` additions carry over as live has them.

Merge waits on Q1. AC adds: the Starlink router may not hold a DHCP reservation, so record how the address is kept stable, or that it is not, as a known risk.

**Q3. Which object classes?** *Cowork ruling: the same eight classes the other cameras track.*
Detector cost is per frame, not per class. The extra cost is events and snapshot storage, which W-9 and W-12 retention already governs. One list across cameras is also what W-26 (#113) will derive from the ontology. Nate may narrow it on the PR.

**Q4. Accept more false positives for fewer misses (#97)?** *Nate decides. Recommendation: yes in principle, but only measured.*
#97 cannot merge as it stands. It tunes `front_door`, which no longer exists, and it would overwrite the live file. Ruling on the PR:
- Close #97 unmerged.
- After W-39 merges and detection has run 24 h at default sensitivity (the W-5 baseline, now on `cabin_outside_reolink`), Code opens a fresh tuning PR with the same three values.
- Post the baseline, merge, and post the 24 h after-measurement.
- Rollback: revert the commit.

The trade-off question returns to Nate with numbers then, not now.

**Q5. `eno2` default route?** *Cowork ruling: accept and document now. Decide never-default at the next site visit.*
The new path works (Cloudflare Tunnel and Tailscale are both up). Changing routes remotely risks cutting off the host, and the runbook requires Nate on site with the timed revert. Code documents the current state in `docs/MAINTENANCE.md` (#119 is the natural home), including the vestigial `192.168.3.1/24` on `cabin-camera-share`. W-40 stays `held` with blocker "next site visit". Recommendation for that visit: a wired uplink is usually the better default, so if the Starlink LAN is the same internet path, keep `eno2` as default and retire the Wi-Fi default instead.

### Live apply of the presence work

**Q6. How to clear the permission block?** *Cowork ruling: neither as asked. Merge first, then Nate runs the staged script.*
- **Merge before apply.** W-23 (#122) and W-33 (#118) are not merged. Applying unmerged code to production breaks the merge-then-deploy rule. Order:
  1. Cowork approval (below).
  2. Merge #122, then #118.
  3. Code re-stages from `main`.
  4. Nate runs the install script, whose rollback is in `PRESENCE.md` and the cabin-security README.
- **No blanket `ssh nate@100.77.44.113` rule.** That grants an unscoped production shell and removes the safety layer that caught this. If remote apply becomes routine, the durable fix is a deploy workflow for `infra/cabin-security/` like the other four. Raise it as a W-item at the next free number with a score proposal.

**#122 and #118: APPROVED WITH CONDITIONS.**
- **C-118-1.** The PR body must say plainly that it changes the repo's flow from **dry-run (both live siren outputs disabled on `main`) to live sirens enabled**, to match the live host. Cowork confirmed this from the diff: on `main` the `LIVE SIRENS ON` and `OFF` nodes carry `d: true`; on #118 they do not. Nate acknowledges it on the PR before merge.
- **C-118-2.** The first live apply runs the README's "Live test, safely" path with the siren output disabled. The output is re-enabled only after a stale and a fresh case each produce the expected audit decision.
- **C-122-1.** `authorized_user_phones.conf` is gitignored and never committed. CI or a test fails if it appears.
- **C-both.** CI green on the merge commit. The governance record from `definition-of-done.md` is in each body.

**#121 (W-34) and #124 (W-35): APPROVED, merge order #121 then #124.** They conflict in `App.jsx` (checked with `git merge-tree`). #124 merges `main` after #121 lands and re-runs Vitest. **#126: APPROVED, merge.**

### Presence and roles (D24, #125)

**Q7. A maintenance-only person sets off the siren unless they disarm first (Q-LR-2).** *Nate decides. Recommendation: intended.*
That is the ordinary alarm model, and it follows from "a guest for presence". The maintenance role holds credentials to disarm, so the fix is to disarm, not to be recognized. Put one line in the setup docs: "maintenance visitors disarm on arrival."

**Q8. Record maintenance visits as audit entries (Q-LR-1)?** *Nate decides. Recommendation: yes.*
They are recorded as an audit event and are not counted as presence. Contractor and service visits are exactly the record the cabin claim keeps needing. Cost is small and folds into W-38.

**Q9. Children: primary role or aggregate-only (Q-LR-3)?** *Nate decides. Recommendation: both, split by purpose.*
- **Gating:** primary role, so a child arriving with friends while the cabin is armed is recognized and does not trip the siren.
- **Display:** aggregate only ("household member present"). No name, no location history, consistent with D11 data minimization.

W-44's use-case pass covers the friends.

**Q10. Is the W-35 banner wording right?** *Cowork ruling: the wording is right. Keep the gate as is unless Nate says otherwise.*
The banner describes what the gate actually does. Sounding the siren for recognized authorized users would make every arrival at an armed cabin a false alarm. If Nate wants that, it is a gate change with its own W-item, not a wording change.

**Q11. A hub arm control with a confirm dialog?** *Nate decides. Recommendation: not now.*
It is a new write path into Home Assistant's security state, so it needs a role check, an R-GD-consistent denial and an audit trail. The W-35 banner covers the stated need. If Nate wants it, Code raises a W-item at the next free number. Cowork's provisional score is 4+5+3+2 = 14, size 3, 4.7.

**Q12. Prototype Tailscale as a presence source, read-only?** *Cowork ruling: yes, green-lit within these limits.*
- Read-only `tailscale status --json` on the M920q.
- Report only for the devices listed in `authorized_user_phones.conf`.
- Do not commit, paste or attach the tailnet dump: it lists every household device.
- Results go on a W-36 comment or in a short doc PR. Report what is observed: `Online`, `LastSeen`, whether `CurAddr` shows a cabin-LAN direct path, and how it behaves when the phone is idle.
- No HA or backend change.

**D24: ratification deferred** until Nate answers Q7 to Q9 (or accepts the recommendations). Then Cowork ratifies in a PR to `decisions/` and W-38 unblocks.

## 4. Other items Code put to Cowork

| Item | Ruling |
|---|---|
| DEP.2 (manifest sources Ask cannot serve) | Annotate each of the nine questions as **grader-only** unless the source is already inside the allowlist. Ask-serving `MAINTENANCE.md` would expose operator detail to household roles. Codex owns the edit |
| DOC-6 / #99 | **Close #99 unmerged.** #109 superseded its rotate-secrets claims, and anything still true belongs in a fresh PR against the current playbook. DOC-6 closed, no score |
| W-32 score | **Confirmed:** 35, size 2, **17.5**. Go, same day, with the matching UI change (`authedFetch` for add-a-place) |
| Vault commits to `main` | **Exemption granted** for vault-only commits made by `rotate-secrets.yml`. Conditions: the commit touches only the vault file; the message names the run; and the pre-push check refuses any non-vault path. Human-made vault commits (like `8348458`, `5bbc751`, `989fb07`) go through a PR from now on. Code records this as D-NEW at the next free D-number in a governance PR, with a DL entry ratifying those three commits after the fact |
| W-27 / W-28 components | W-27 = 8+7+4+5 = 24, size 5, **4.8**. W-28 = 4+6+2+4 = 16, size 3, **5.33**. #111's owner merges `main` and keeps both `WebConfig` patterns |
| `GET /api/frigate-metrics` and JSON-LD routes | **Leave open** under D14 (camera health and ontology structure reveal no occupancy). Revisit if W-43 changes D14 |
| D23 (#116) | **Ratify** once Nate confirms Q-HD-1 and Q-HD-2. Recommendation: yes, retire `locations/home/` and the CLAUDE.md "Home hub" column; rename `HOME_HUB_DEPLOYED` to `HOME_HA_DEPLOYED`. Then #123's note is replaced by a rewrite of the table |
| Stale #105 row on #112 | Code updates HO-2026-10-03 Task 3, row #105, to "closed unmerged 2026-10-04; camera reconnected out of band via `eno2`; see W-15" |
| W-15 score | Code's 30 / 3 / 10.0 confirmed after the fact |

## 5. Correction from Cowork

On 2026-10-03 Cowork told Nate that W-15 was likely complete at `192.168.2.200` and gave Code a check against `front_door`. That was wrong on both counts. The camera was cabled to the Starlink LAN at `192.168.1.121` via `eno2` and renamed, as Code had already recorded in #120. Code's handling of #105 stands, and that instruction is withdrawn.

## 6. Code task list from this file

1. Apply §1 renumbering and §2 scores to the rows on #120 and #117 (add to those PRs, do not open new ones).
2. Post the conditions in §3 on #118 and #122, and the merge-order note on #121 and #124. Merge #126, #121, then #124 after CI.
3. Close #97 and #99 with a comment linking this file.
4. Open the W-39 draft (Q2).
5. W-32, same day.
6. Governance PR: vault D-NEW and DL entry (§4).
7. Tailscale read-only prototype (Q12).
8. Update the #105 row on #112.
9. Wait on Nate for Q1, Q4, Q7 to Q9, Q11 and D23's Q-HD-1 and Q-HD-2. Record his answers on #125 (D24 questions), the W-39 draft (Q1), and #116 (D23).

## Closure

This file closes with HO-2026-10-04-code-to-cowork. The PR that merges the last of #112, #113, #114, #116, #117, #118, #120, #121, #122, #124, #125 and #126 deletes both files. Open items move first to their permanent homes: a backlog row, a DL entry or a decision question.
