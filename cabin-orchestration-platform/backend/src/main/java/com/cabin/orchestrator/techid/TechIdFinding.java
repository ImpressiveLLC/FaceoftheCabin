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
 *
 * Product-facing name is "Opportunity" (see docs/PRODUCT_NOTES.md's
 * 2026-08-03 UX Lead / Application Architect review) -- kept as
 * TechIdFinding at the code/table layer to avoid a second rename this
 * session; `ui_display_name` in docs/ontology.yaml's tech_id_finding
 * entity is where the product label actually lives.
 *
 * entityId is the primary ontology entity this finding is about.
 * relatedEntityIds holds any additional entities referenced by the
 * finding -- e.g. a "your existing Frigate camera can already do OCR
 * fallback" finding on a Kidde alarm lists the camera entity here too.
 * Every id in both fields is a lineage claim: it MUST correspond to a
 * real entity already in docs/ontology.yaml. **Not server-validated
 * today** -- the backend has no in-memory index of ontology.yaml
 * (see OntologyLookupService's own javadoc for why) -- so this is
 * currently enforced only by convention: the reference routine reads
 * ontology.yaml before writing an id, and other providers are expected
 * to do the same. A documented, honest gap, not a silent one.
 *
 * actionable is optional and only set when the finding maps to
 * something the user can do immediately with capabilities the platform
 * (or a device they already own) already has -- the "Act: do the thing
 * I want" path from the Opportunity Map's See/Think/Act model. Null
 * when the only real actions are "buy something new" or "ask the
 * platform operator to build this."
 */
public record TechIdFinding(
    String id,
    String entityId,
    java.util.List<String> relatedEntityIds,
    String provider,
    String findingType,
    String summary,
    String confidence,
    java.util.List<String> sources,
    Actionable actionable,
    String status,
    long checkedAt,
    long createdAt
) {
    /**
     * mode: "self_serve" (detail/url describe how to do it now with what's
     * already installed) -- the only mode the Opportunity Map's "Do it now"
     * button renders for. Other modes (e.g. a provider tagging its own
     * suggested purchase link) are stored but currently unused by the UI,
     * which instead lets the user act on `sources` directly for the "buy
     * elsewhere" path.
     */
    public record Actionable(String mode, String detail, String url) {}
}
