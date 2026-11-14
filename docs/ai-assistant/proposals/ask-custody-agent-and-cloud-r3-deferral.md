# Ask: custody agent role and the Cloud r3 deferral record

Drafted by Code on 2026-11-14 at the owner's request, for Codex and Code to review and confer on through [issue #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244). This document authorizes nothing by itself: it records a decision that so far lived only in one issue comment, and proposes a role description for review.

## 1. Record: Cloud r3 is deferred, not rejected

Source: [#244 comment 6873114723](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873114723), Cowork, 2026-11-14 00:27 CST, recorded as the owner's decisions.

| Item | Decision |
|---|---|
| Cloud r3 (hash `057aed8d`) | **Not accepted now.** The owner has no account and not enough information. Disposition: deferred, not rejected. |
| What the owner wants instead | A workaround or alternative: wait and buy a machine, or a dedicated local device (a factory-reset phone that can be wiped). |
| All-local custody path | Kit r4 Part 3, **unchanged**. |
| Open for Codex | Whether a dedicated reset phone can meet the isolation and effective-authority tests, and who the eligible custodian-operator is. That person is still unnamed, and a device does not solve that. |
| Machine purchase | Only the lucite-bakelite P340 listing (USD 499, 16 GB, no GPU) is dropped, unless it proves likely and add-ons are cheap. Other P340 and tower options stay open. No seller inquiry was sent. |
| Training | **None authorized.** The earlier (a) ruling is the S1 contrast-pair choice, not a training go. Offline prep under the owner's roadmap proxy may proceed. |

Where the material is: the r3 text and Codex's review are sealed-adjacent drafts in the hub inbox on the owner's Drive. They are not in git, on purpose, so this document does not reproduce or summarize them.

The gap this closes: until this file, the deferral was in no document on `main`, so a new session would find it only by reading #244.

## 2. Draft role: custody agent

### Purpose

Hold the sealed material for the Ask acceptance evaluation and enforce the rules for when and how it opens, so that the people and sessions who write, run and grade the evaluation never see what they must not. The sealed acceptance set, its key and its hash lock are described in [the public QA dataset register](ask-public-qa-dataset-register.md) (blind custodian, hash-locked, aggregate-only disclosure) and the unmasking barrier is pinned in [`eval-fixture/README.md`](../eval-fixture/README.md).

### Operator and agent are different things

- The **custodian-operator** is a named, eligible person. The role needs one, and none is named yet. That is a decision for the owner and is not made here.
- A **custody agent** is tooling that acts under the operator: it holds files, checks hashes, runs the unmasking barrier and reports aggregates. It is not itself the custodian and cannot satisfy the custodian-eligibility test.
- A general-purpose coding session that has read the repository or any sealed item is not isolated from the graders. Giving it the title does not change that, and a container around it does not either.

### What the custody agent may do

1. Hold the sealed set, the key and the grader pack in the custody environment only.
2. Verify hashes against the frozen manifest before any run and before the key opens.
3. Run the unmasking barrier exactly as pinned: two independent grade files and the adjudication must be present and match the manifest before the key is read.
4. Disclose aggregates only (for example the per-arm gate values and the verdict), never an item, an answer, a grade or the key.
5. Log every access to the custody environment.

### What it may not do

1. Author, edit, run or grade any evaluation item.
2. Read any sealed item outside the custody environment, or write any part of one to git, a chat, a log or a cache.
3. Release sealed content. Release is a separate decision that is not covered by this document or by the owner's roadmap proxy.
4. Train, export, purchase, create accounts or handle credentials.

### Environment options

| Option | State | Needs |
|---|---|---|
| All-local, kit r4 Part 3 | Unchanged and available as the baseline | A named custodian-operator; the isolation tests below |
| Dedicated factory-reset phone | Proposed by the owner; Codex to rule | Must pass the isolation and effective-authority tests; named operator |
| Wait and buy a machine | Open | Owner purchase decision; same tests |
| Cloud r3 | **Deferred** | An account and more information from the owner |

### Required evidence before any option is used

These are separate from the fixture's unit tests (the README says directory separation of the key is layout, not custody): host and account isolation, external-egress scans, log, cache and exception sentinel scans, and unauthorized-reader checks, all run in the custody environment, plus the effective-authority test Codex has been asked to define for the reset-phone option.

## 3. Open questions

1. **Who is the custodian-operator?** Owner decision. Blocks every option.
2. **Does a reset phone meet the isolation and effective-authority tests?** Codex to rule.
3. **Which option is the baseline while 2 is open?** Proposed: kit r4 Part 3, since the owner said it is unchanged.
4. **What does the custody agent run as?** A tooling process under the operator in the chosen environment, not a coding session in this repository.

## 4. What this document is not

- Not an execution go, not a training authorization, not a purchase, not an account, not a release of any sealed item.
- Not permission for any action the 2026-11-14 note does not list. Omission is not permission: anything not stated here stays as it was.
- Not a statement about the contents of r3, which this repository does not hold.
