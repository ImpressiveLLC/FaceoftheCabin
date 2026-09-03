package com.cabin.orchestrator.platformimport;

import java.util.List;

/**
 * D10's "AI translation layer" step, made deterministic and unit-testable
 * (WSJF #9's own success criterion: fixture-driven, no live API needed --
 * this is a plain mapping function, not an LLM call). entityIdCandidate is
 * a proposal only, never persisted until a person confirms it (D10:
 * "entity_id not written until confirmed").
 */
public record ImportProposal(
    String platform,
    String originalId,
    String originalName,
    String originalLocation,
    String entityIdCandidate,
    List<String> measurementTypeCandidates,
    String confidence // "HIGH" | "MEDIUM" | "LOW"
) {}
