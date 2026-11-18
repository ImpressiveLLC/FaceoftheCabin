# W-117 C2 slice 2 evidence

Source baseline: `b9eea9cd649dd716672fc574c39aaf5e52b887d9`. Status: source review and offline deterministic context checks only. No live Ask, model execution, ingestion into knowledge_node, training, host change, restore rehearsal or answer-quality score. A1 NOT_MEASURED; release_eligible remains false.

## Scope and corrections to the proposed gap list

Ten separately mapped H2 sections in [operations-recovery-reference.md](../user-guide/operations-recovery-reference.md). Eight are administrator-only; incident triage and top-level Grafana login are household guidance without admin commands, addresses or credentials. Each section includes source references and baseline provenance. Existing guide text is not rewritten.

- There are 16 workflow YAML files, not 17; the scripts directory is not a workflow.
- Eleven test/tooling workflows exist, but seven were already named in maintenance.md's deployment table. The new section consolidates all eleven rather than claiming eleven newly documented workflows.
- The existing guide already inventories backup gaps. This slice gives a concise per-store status, not invented restore instructions or a claim of a fresh host audit.
- The manual heartbeat check can prepare its state directory. The new guide does not repeat the stronger historical "read-only" claim.
- Node-RED's role tests authorized login after a change, but its stock-block replacement and conditional checks do not make it a general credential-rotation procedure.
- The Worker bounds deliberate-stop suppression; an indefinitely silent intentionally stopped service is not permanently muted.
- A successful retry of the Family Hub test does not establish the intermittent failure's root cause.

## Offline checks

| Check | Result |
|---|---|
| Twenty new normal/failure questions, two per section | All select the intended category and deliver the complete expected section in actual AskContextBuilder tests |
| Role denial | Every non-admin role and null denied admin-only sections; household sections remain available |
| Mixed question: monitoring key plus external heartbeat | Both complete sections delivered, no truncation |
| Fixture mirror | Docs/backend bytes identical |
| Mapped section size | Largest raw section 2,360 characters; every category maps one section, below 6,000 |
| Source links | All relative file targets exist; deployment-table anchor checked |
| New guide scan | No literal IPv4 address; no instance hostnames, tokens, passwords or key values introduced; source links and setting names only |
| Targeted Maven tests | 42 tests, zero failures/errors/skips: matcher9, builder20, recovery-reference4, document-source9 |
| Full backend attempt on the baseline | 1,082 tests reported; 2 existing PlatformSpecsGuard failures (repair #485), 40 Docker/Testcontainers environment errors, 4 skips. Not green; no claim Docker integration was verified |

The new four tests are in `AskContextRecoveryReferenceTest`. They inspect actual delivered context text, not merely selected category names, and exercise non-admin denial plus a tied-category request. Acceptance questions live in [operations-recovery-cases-2026-11-18.json](operations-recovery-cases-2026-11-18.json), outside the reviewed-document allowlist. They are authored regression cases, not gold, sealed material or a graded model evaluation.

## Requested route.py before/after

Executed the existing script against baseline and changed maps:

- New 20 questions: no-category count 4 before, 0 after; all 20 select the new intended categories after.
- Existing 150 phrasings: no-category count 16 before and after; complete output unchanged.

Limitation: route.py implements the older plural-only matcher and first winner; current Java additionally supports more endings and tied-category union. These script counts are explicitly replica outputs, not proof of current runtime routing on arbitrary phrases. The actual Java tests above are the acceptance evidence for the new questions and tie behavior. The routing implementation itself is unchanged.

## Integrated-main validation (2026-11-18)

Merged main `888dab9acb4a62761659a38d9b348127761e3834` after #485 and #482. Resolved the two append-only fixture conflicts by checking both original prefixes against the common base, preserving all five peer configuration categories and all ten recovery categories, and retaining both change notes. Both fixture copies remain byte-identical; category IDs are unique.

On the integrated tree, 59 targeted tests passed with zero failures/errors/skips: matcher 9, builder 22, recovery reference 4, document source 9, platform-spec guard 7, platform-info service 8. The source-link/section-budget scan and route.py comparisons above were rerun with unchanged results. This fixes the two old-baseline platform-spec failures; local Docker remains unavailable, so the full integration suite was not rerun or represented as green. Remote exact-head CI remains a merge gate.

## Integration and release gates

#482 and this slice both append to the fixture map. Merge #482 first after its repair and #485; this slice's author merges current main, preserves both category sets, reruns parity and affected checks, and posts a new full-head READY. Cowork independently reviews this authored slice and owns the pinned merge/deploy gate. No author self-CLEAN.

Backend resource/test paths trigger CI and deployment on merge; this is not a docs-only/no-deploy PR. Local Docker integration is unavailable, so applicable remote backend checks must pass. A deployment/live-health check does not measure Ask answer quality or authorize a live model evaluation.
