package com.cabin.orchestrator.platformimport;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * D10's "AI translation layer" -- deterministic on purpose (WSJF #9's own
 * success criterion needs this fixture-testable with no live API), not an
 * LLM call. Confidence is a simple, explainable heuristic over how many
 * measurement_type candidates the adapter already inferred -- never a
 * fabricated score (D8's "zero fabricated sources" hard gate applies here too).
 */
@Service
public class PlatformImportTranslationService {

    public ImportProposal propose(RawImportRecord raw) {
        String entityIdCandidate = raw.platform() + "-" + slugify(raw.originalName());
        return new ImportProposal(raw.platform(), raw.originalId(), raw.originalName(), raw.originalLocation(),
            entityIdCandidate, raw.measurementTypeCandidates(), confidenceFor(raw));
    }

    private static String confidenceFor(RawImportRecord raw) {
        int n = raw.measurementTypeCandidates() == null ? 0 : raw.measurementTypeCandidates().size();
        if (n == 1) return "HIGH";
        if (n > 1) return "MEDIUM";
        return "LOW";
    }

    private static String slugify(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String slug = name.toLowerCase(Locale.ROOT).trim()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "unnamed" : slug;
    }
}
