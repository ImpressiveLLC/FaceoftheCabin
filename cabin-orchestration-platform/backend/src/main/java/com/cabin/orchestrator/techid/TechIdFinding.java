package com.cabin.orchestrator.techid;

/**
 * A single research finding about a cataloged ontology entity, submitted
 * by ANY tech-scanning provider -- the reference Claude Code routine,
 * an operator's higher-tier paid scanning service, or an instance
 * owner's own AI platform of choice. This record is deliberately
 * provider-agnostic: `provider` is free text, not an enum, so this
 * never needs a code change to accept a new source.
 *
 * findingType, confidence, and sources are the normalized shape every
 * provider must produce regardless of how they actually did the
 * research (web search, vendor API polling, a human reading a
 * whitepaper) -- see docs/ROADMAP.md's "Tech ID Service — Provider
 * Model" section for the full contract.
 */
public record TechIdFinding(
    String id,
    String entityId,
    String provider,
    String findingType,
    String summary,
    String confidence,
    java.util.List<String> sources,
    String status,
    long checkedAt,
    long createdAt
) {}
