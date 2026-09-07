# Ask and platform knowledge

Audience: operator/contributor. Current implementation facts below are pinned through the corpus baseline; the desired comprehensive assistant remains under development.

## Current behavior

The native Ask panel calls `POST /api/helpdesk/ask` with `question`. The response has `question`, `answer`, `sources` (KnowledgeNode records) and `answeredByModel`. The service retrieves at most five KB nodes by word overlap, applies credential-pointer redaction, calls Ollama and returns either the model text or a raw-facts fallback. With no matching facts it refuses. Source: [controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/TinyHelpdeskController.java), [service](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java), [response](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskAnswer.java), [panel](../../../cabin-orchestration-platform/ui/src/App.jsx).

This is not yet the proposed complete documentation assistant. Committing Markdown does not automatically make it available to this service. No document ingester, vector migration or replacement endpoint is included in corpus foundation r1.

## What a source currently means

A KnowledgeNode contains `entityRef`, `chunkType`, `content`, `source` and `generatedAt`. Its repository key is entity reference plus chunk type. A returned node is retrieved context; it does not prove that each sentence in a generated answer follows from that source. Document name/section citations need an additive, compatible design before the broader corpus is integrated. Source: [KnowledgeNode](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/KnowledgeNode.java), [repository](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/JdbcKnowledgeNodeRepository.java).

The shared vocabulary is served at `/api/context/cabin-context.jsonld`. Reuse it; do not invent a second namespace to accommodate documents. Types are DESCRIPTION, SETUP, TROUBLESHOOTING, CREDENTIAL_POINTER and RELATIONSHIP. Source: [context](../../../cabin-orchestration-platform/backend/src/main/resources/context/cabin-context.jsonld), [KnowledgeChunkType](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/KnowledgeChunkType.java).

## Questions and useful limits

- A supported operational answer should cite the actual document/section and match the user's role, instance and version.
- A request for current telemetry needs an authorized live read, not an example reading in static documentation.
- Missing or contradictory operational evidence should produce a localized explanation of the gap and a supported next step; it must not turn into a global “wait for ontology decisions” response.
- A safety procedure requires applicable reviewed content; descriptions of a valve do not justify invented reset instructions.
- An admin credential question may return a vault entry name; household responses must not expose it or other operator-only details.

These are acceptance rules from the handover and [eval set](../rag/eval-questions.md), not claims all enforcement is implemented in the existing service. Its prompt asks the LLM to stay grounded, but runtime tests must assess the actual output.

## Add or correct knowledge

Find the relevant canonical document using [coverage](../corpus/coverage.md), update the source-grounded section in a PR, add/adjust expected-answer evals and retain the source version. Do not invoke KB regeneration expecting it to ingest this documentation: the [KB generator](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/KbGeneratorService.java) generates device metadata, not a Markdown corpus. Do not overload the existing natural key with arbitrary document sections that overwrite prior content.

The later evaluated pipeline must first compare the current service and a role-filtered whole-context baseline. Add retrieval when context/resource measurements justify it, and measure knowledge coverage, citations, task success and refusals. Facts remain in maintained documents; changing the model is not a substitute for fixing a missing procedure. New model choice, runtime mutations and safety actions retain their separate authorization requirements.
