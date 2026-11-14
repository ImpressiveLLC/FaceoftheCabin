# Ask: custody roles, access matrix and the Cloud r3 deferral

Drafted by Code on 2026-11-14 at the owner's request and repaired the same day after Codex's exact-head review (HOLD on `64995727`) and Cowork's confirmed access design, for Codex and Code to review and confer on through [issue #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244). This document authorizes nothing by itself: it records decisions that so far lived only in issue comments and states the role and access design for review. It reproduces no sealed content.

## 1. Authority, in order

The 00:27 text is kept as history. The current authority is the later amendments, and each limit below stays distinct.

| When (CST, Sat 14 Nov) | Source | What it says | Status |
|---|---|---|---|
| 00:27 | [#244 comment 6873114723](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873114723) | Cloud r3 (hash `057aed8d`) deferred, not rejected; all-local kit r4 Part 3 unchanged; only the lucite-bakelite P340 listing dropped; the custodian was required to be an unnamed person; training and release were excluded from the roadmap proxy. | **Historical.** The unnamed-person requirement and the exclusion of release and training are superseded by the rows below. The Cloud r3 deferral and the machine-purchase line still stand. |
| 00:28 | [6873120374](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873120374) | Owner proxy grant on the roadmap, 12 hours from 00:27, lapsing at 12:27 CST unless renewed. | Current, time-boxed. |
| 00:30 | [6873152071](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873152071) | Owner amendment: deploying merges, sealed release, training or export, credentials and irreversible actions are no longer excluded by the proxy. **Purchases of any kind stay banned.** Anything irreversible needs the written two-key record on #244 (what, why, rollback or none, exact target, plus a Code comment and a Codex comment that it makes sense, each bound to the exact plan). Custody and sealed-set prerequisites, the A1 gates (NOT_MEASURED, `release_eligible` false until measured), and contamination and preregistration rules are gates, not permissions, and are unchanged. | Current. |
| 01:07 | [6873567919](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873567919) | Owner decision and Cowork ruling: an agent may hold the custody role under an owner waiver, with the physical store, courier and risk label described in section 2. This waives the earlier rule that the custodian is not an agent. | Current, recorded as the owner's waiver. |
| 01:08 | [6873603622](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873603622) | Codex claims the custody-lead role under the waiver and amends the physical design (metadata and control role separate from protected execution; no coding or trainer session receives sealed plaintext; the S20 phone is a candidate, not yet qualified). | Current. |
| 01:32 | [6873868052](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873868052), confirmed by Codex at [6873921147](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873921147) | Access matrix v2 (section 3) and training contract v1. | Current design; not an execution key. |

Distinct gates, never blended: the 12-hour window; the purchase ban; the two-key rule for anything irreversible; and the evidence gates (custody qualification, independent grading, acceptance). A proxy approval does not waive a failed gate, and nothing here supplies either key.

## 2. Role design and decision record

### Purpose

Hold the sealed material for the Ask acceptance evaluation and enforce when and how it opens, so the sessions that write, run, train on or grade the evaluation never see what they must not. The sealed set, its key and its hash lock are described in [the public QA dataset register](ask-public-qa-dataset-register.md) (blind custodian, hash-locked, aggregate-only disclosure) and the unmasking barrier is pinned in [`eval-fixture/README.md`](../eval-fixture/README.md).

### Current decisions

- The custodian-of-record is a **metadata and control role**. It holds the manifest, the hash lock, the role roster, the release log and the gate decision. It does not hold sealed plaintext or the key. Protected execution is a **separate constrained process**.
- No orchestration, coding or trainer session receives sealed plaintext. A separately authorized, isolated grader process receives its **minimum blinded pack**; that is the only sealed-adjacent content any agent process sees. The arm key stays outside grader access.
- Where each grader runs, and whether any provider receives protected content, is declared before release. There is no hidden hosted transit.
- Plaintext and the key live on a physical store that no orchestration, coding or trainer session can read. The wiped Samsung S20 is a **candidate, not yet qualified**: nothing yet shows it supports the full local runner and grader workflow or blocks privileged, log or backup access, and an offline, no-account posture does not prove either. Training compute is chosen separately from acceptance custody.
- The courier (Nate) carries and powers the device and is never custodian, grader or reader of items. A courier is a declared role, not a technical control. A human button press is not independence if that human or an administrator can inspect or alter everything.
- Every result carries the label **"agent custody, owner waiver"**. It is not independent human custody.
- Someone must physically operate the device for unmask and release; the courier does that on the custodian's written instruction with no view of items.

### Operator, control and protected process are different things

- **Custody control** (metadata role): acts on ids, counts, hashes and aggregates. It cannot rewrite a frozen manifest or its own approval. Control changes are append-only and versioned and each needs independent revalidation by the barrier verifier.
- **Protected runner**: a constrained process at a pinned runner SHA. It handles sealed items in memory only and delivers blinded packs. It creates controlled blinded artifacts and never the arm-specific results.
- **Protected analyzer**: the only principal with conditional access to the arm key, and only after the complete barrier in section 3 passes. It produces approved aggregates only. No key is returned to any chat, grader, trainer or custody session.
- A general-purpose coding session that has read the repository is **not** disqualified for that reason alone (see the criteria below), but a session label or a container around it proves nothing about independence either.

### Eligibility criteria (replaces blanket exclusion)

Eligibility for any role is judged on three things, recorded per role on the plan:

1. **Actual exposure**: which protected information (sealed items, the pack, grades, the key) the principal actually had access to. Reading the public repository alone is not disqualifying exposure.
2. **Conflicting roles**: for example an author never verifies the barrier or the content it authored, a trainer never holds sealed data, and an agent that trains or tunes on rows does not audit them.
3. **Effective access**: who can read files, keys, process memory, logs, caches, backups or grader outputs, and who can change the runner, the manifest, the policies and the released results. This includes administrator, device-owner, backup and recovery paths. Shared privileged access defeats a claimed boundary regardless of device count.

A container, a session label or a fresh context window does not cure exposure and is not evidence of independence. A fresh-context agent may verify sealed-pack content only after exposure, independent generation and review roles, actual access isolation and provider transit are established.

### Environment options

| Option | State | Needs |
|---|---|---|
| All-local, kit r4 Part 3 | Unchanged; available as the baseline | The role design above and the qualification evidence below |
| Dedicated factory-reset phone (S20) | Candidate, not yet qualified | The qualification cases below |
| Wait and buy a machine | Open | An owner purchase decision (purchases are banned under the grant) and the same qualification |
| Cloud r3 | **Deferred, not rejected** | An account and more information from the owner |

### Qualification evidence required before any option is used

Separate from the fixture's unit tests (the README says directory separation of the key is layout, not custody). Invented canaries first, bound to the exact device, OS, accounts, runner SHA and configuration:

- denied reads by trainer, grader and orchestration principals; no grader access to the arm key;
- network-egress scans; log, cache, backup and exception-leak scans;
- full manifest, pack, grade and adjudication validation before any key read; changed, partial or missing inputs fail closed with zero key reads;
- approved outputs only; recovery copies and stale, replayed or concurrent job rejection;
- physical and administrator bypass and actor overlap, explicitly disclosed.

All of this is `NOT_RUN` on a real environment. Real training is `NOT_RUN`. The A1 gates are `NOT_MEASURED` and `release_eligible` is false.

## 3. Access matrix v2

Scope: this matrix is the **Ask paired-arm (A1, factual QA120)** mechanism. It is not silently applied to the intent frozen-200 plus safety-80 acceptance, which needs its own explicit design (training contract R7).

Legend: S = sealed items (frozen test, safety set); P = blinded grader pack; K = arm key; G = grader grade files; M = manifest and hashes; A = approved aggregates.

| Role | Holder | S | P | K | G | M | A |
|---|---|---|---|---|---|---|---|
| Custody control | Codex (metadata role) | no | no | no | no | read and write, append-only and versioned | read and write |
| Protected runner | constrained process, pinned runner SHA | in memory only | delivers | no | no | read | writes |
| Protected analyzer | pinned deterministic process, pinned SHA | no | no | conditional, only after the full barrier passes | reads the complete set | read | writes |
| Trainer | Code or a delegated worker | NEVER | no | no | no | train manifest only | dev only |
| Blind author | Cowork (authored the sealed rows only) | knows what it authored | no | no | no | read | no |
| Domain verifier | a distinct eligible principal, named before freeze | named per the criteria above | no | no | no | read | no |
| Barrier verifier | fresh-context agent that is not Cowork, Codex or Code, or an independent execution record | no | no | no | reads the complete set | read | read |
| Grader 1 and Grader 2 | separate blinded fresh agents | no | own blinded pack only | no | own only | no | no |
| Adjudicator | separate blinded fresh agent | no | disputed rows only | no | both | no | no |
| Plan, hash and evidence verifier | Cowork | no | no | no | no | read | read after unmask |
| Courier | Nate | the physical device only | no | no | no | no | after release |

Rules:

1. **Full canonical barrier before any key read.** Complete, well-formed grade coverage from both graders; every disagreement resolved by the adjudicator exactly; manifest and pack validation passed. Any failure means zero key reads and the run fails closed. Hash presence alone is not enough, and pack and grade record validity may need controlled protected validation, not a metadata-only assertion.
2. **Blind author and domain verifier are separate principals.** Cowork authored the sealed rows, so Cowork never verifies those rows or the barrier. Cowork keeps plan, hash and evidence verification.
3. **Controlled delivery is not external disclosure.** Delivering a blinded pack to a grader process is an in-custody step. External disclosure is approved aggregates only. Where a grader runs and whether any provider receives protected content is declared before release.
4. **Metadata logs** carry ids, counts and hashes only, never protected content.
5. **Actor overlap and effective access** are recorded per role and shown on the plan.
6. **Custody control may not rewrite a frozen manifest or its own approval.** Changes are append-only, versioned and independently revalidated by the barrier verifier.

## 4. Open questions

1. **Who fills each role?** The roles in section 3 are named; the domain verifier and the barrier verifier, and any real-environment operator, are still to be named before freeze. This replaces the earlier question "who is the custodian-operator", which the owner's waiver superseded.
2. **Does the S20 phone, or another device, pass the qualification evidence?** Codex to rule once evidence exists.
3. **Which option is the baseline while 2 is open?** Proposed: kit r4 Part 3, which the owner said is unchanged.
4. **Training compute** is selected separately from custody and is tracked in the training contract, not here.

## 5. What this document is not

- Not an execution go, not a training authorization, not a purchase, not an account, not a release of any sealed item, and not either of the two keys.
- Not permission for any action the 2026-11-14 notes do not list. Omission is not permission: anything not stated here stays as it was.
- Not a statement about the contents of r3, which this repository does not hold. The r3 text and Codex's review are sealed-adjacent drafts held outside git.
