package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * C1a deterministic context injection: reads a keyword-pattern -> context map
 * from docs/ai-assistant/rag/context-fixtures-r2.json (see that file's own
 * "description" field, which is the selection rule's specification) at
 * startup and, per question, supplies a fixed set of context instead of
 * relying on TinyHelpdeskService's word-overlap retrieval.
 *
 * <p>r2 (2026-09-20) over r1: patterns match whole words/phrases only, and the
 * highest-scoring category wins (r1's substring first-match sent five frozen
 * questions to the wrong category); categories can also name reviewed
 * repository Markdown sections ({@link ReviewedDocumentSource}), because the
 * database holds none of the platform./system./family_hub. KnowledgeNodes r1
 * designates. Document sections are role-gated fail-closed: an entry whose
 * audience is "administrator" (the default) is only supplied to an
 * ADMINISTRATOR; a null or any other role gets only "household" entries.
 */
@Component
public class AskContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AskContextBuilder.class);

    /** Below this remaining budget a section is skipped rather than truncated to a stub. */
    private static final int MIN_USEFUL_SECTION_CHARS = 300;
    private static final String DOCUMENT_REF_PREFIX = "doc:";
    /**
     * Endings a keyword may take and still count as that keyword: plural, past, progressive, adverb,
     * agent and -ation noun forms (install -> installs, installed, installing, installation). A keyword
     * ending in "e" also takes -ing and -ation after dropping it (configure -> configuring). Doubled
     * letters and other stem changes are not handled; list that form as its own keyword where it matters.
     */
    private static final String INFLECTION = "(?:s|es|ed|d|ing|ings|ly|er|ers|ation|ations)?";

    private final KnowledgeNodeRepository knowledgeNodeRepository;
    private final ReviewedDocumentSource documentSource;
    private final List<CompiledCategory> categories;
    private final int maxDocumentChars;

    public AskContextBuilder(
            KnowledgeNodeRepository knowledgeNodeRepository,
            ReviewedDocumentSource documentSource,
            ResourceLoader resourceLoader,
            @Value("${ask.context.fixtures:classpath:rag/context-fixtures-r2.json}") String fixturesLocation,
            // ~4 chars/token: 6000 chars is ~1.5k tokens, sized to stay inside a default Ollama
            // context window with the prompt scaffold and answer. Measure before raising (C1's
            // context-fit rule); Ollama silently drops the *start* of an oversized prompt.
            @Value("${ask.context.max-document-chars:6000}") int maxDocumentChars) {
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.documentSource = documentSource;
        this.maxDocumentChars = maxDocumentChars;
        this.categories = loadCategories(resourceLoader, fixturesLocation).stream()
            .map(CompiledCategory::new)
            .toList();
        log.info("AskContextBuilder loaded {} categories from {} (document budget {} chars)",
            categories.size(), fixturesLocation, maxDocumentChars);
    }

    /** Role-less overload: equivalent to a null role, i.e. household-audience document entries only. */
    public List<KnowledgeNode> buildContext(String question) {
        return buildContext(question, null);
    }

    public List<KnowledgeNode> buildContext(String question, HouseholdRole role) {
        List<CompiledCategory> selected = select(question);
        if (selected.isEmpty()) {
            return List.of();
        }
        List<KnowledgeNode> nodes = new ArrayList<>();
        List<KnowledgeNode> documents = new ArrayList<>();
        Set<String> seenNodes = new HashSet<>();
        Set<String> seenDocuments = new HashSet<>();
        int remaining = maxDocumentChars;
        for (int i = 0; i < selected.size(); i++) {
            ContextCategory category = selected.get(i).category();
            for (KnowledgeNode node : resolveNodes(category)) {
                if (seenNodes.add(nodeKey(node.entityRef(), node.chunkType()))) {
                    nodes.add(node);
                }
            }
            // The first (declaration-order) category of a tie keeps the budget-truncation behavior;
            // the other tied categories only add sections that fit whole in what is left.
            List<KnowledgeNode> added = resolveDocuments(category, role, remaining, seenDocuments, i == 0);
            documents.addAll(added);
            remaining -= added.stream().mapToInt(d -> d.content().length()).sum();
        }
        log.info("AskContextBuilder: categories {} -> {} KnowledgeNodes, {} document sections ({} chars){}",
            selected.stream().map(c -> c.category().id()).toList(), nodes.size(), documents.size(),
            documents.stream().mapToInt(d -> d.content().length()).sum(),
            role == HouseholdRole.ADMINISTRATOR ? "" : " [non-admin audience]");
        List<KnowledgeNode> context = new ArrayList<>(nodes);
        context.addAll(documents);
        return List.copyOf(context);
    }

    /** Which category a question routes to first (null if none) -- lets tests assert routing without fixture nodes. */
    String categoryIdFor(String question) {
        List<CompiledCategory> selected = select(question);
        return selected.isEmpty() ? null : selected.get(0).category().id();
    }

    /** Every category that tied for the top score, in declaration order (empty if none matched). */
    List<String> categoryIdsFor(String question) {
        return select(question).stream().map(c -> c.category().id()).toList();
    }

    /**
     * Highest score wins; categories that tie for it are all kept, in declaration order, so a tie no
     * longer drops the right section just because another category was declared first. No matching
     * pattern selects nothing.
     */
    private List<CompiledCategory> select(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<CompiledCategory> best = new ArrayList<>();
        int bestScore = 0;
        for (CompiledCategory candidate : categories) {
            int score = candidate.score(normalized);
            if (score > bestScore) {
                best.clear();
                best.add(candidate);
                bestScore = score;
            } else if (score == bestScore && score > 0) {
                best.add(candidate);
            }
        }
        return best;
    }

    private List<KnowledgeNode> resolveNodes(ContextCategory category) {
        if (category.contextNodes() == null || category.contextNodes().isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeNode> byKey = knowledgeNodeRepository.loadAll().stream()
            .collect(Collectors.toMap(
                node -> nodeKey(node.entityRef(), node.chunkType()),
                node -> node,
                (a, b) -> a));

        return category.contextNodes().stream()
            .map(ref -> {
                String key = nodeKey(ref.entityRef(), ref.chunkType());
                KnowledgeNode node = byKey.get(key);
                if (node == null) {
                    log.warn("AskContextBuilder: category '{}' references missing KnowledgeNode {}",
                        category.id(), key);
                }
                return node;
            })
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * @param remaining       document characters still available across all selected categories
     * @param seen            "path#section" keys already supplied by an earlier tied category (updated here)
     * @param truncateToFit   false for a secondary tied category: a section that does not fit whole is skipped
     */
    private List<KnowledgeNode> resolveDocuments(ContextCategory category, HouseholdRole role, int remaining,
                                                 Set<String> seen, boolean truncateToFit) {
        if (category.contextDocuments() == null || category.contextDocuments().isEmpty()) {
            return List.of();
        }
        boolean administrator = role == HouseholdRole.ADMINISTRATOR;
        List<KnowledgeNode> documents = new ArrayList<>();
        for (ContextDocumentRef ref : category.contextDocuments()) {
            if (!administrator && !"household".equalsIgnoreCase(ref.audience())) {
                continue;
            }
            String key = ref.path() + "#" + ref.section();
            if (seen.contains(key)) {
                continue;
            }
            var section = documentSource.section(ref.path(), ref.section());
            if (section.isEmpty()) {
                continue;
            }
            String text = section.get().text();
            if (text.length() > remaining) {
                if (!truncateToFit) {
                    log.warn("AskContextBuilder: tied category '{}' section {} skipped, does not fit whole in {} chars left",
                        category.id(), key, remaining);
                    continue;
                }
                if (remaining < MIN_USEFUL_SECTION_CHARS) {
                    log.warn("AskContextBuilder: category '{}' section {}#{} skipped, {} of {} chars of budget left",
                        category.id(), ref.path(), ref.section(), remaining, maxDocumentChars);
                    continue;
                }
                text = truncateAtParagraph(text, remaining)
                    + "\n[section truncated to fit the context budget -- see " + ref.path() + "#" + ref.section() + "]";
                log.warn("AskContextBuilder: category '{}' section {}#{} truncated to {} chars",
                    category.id(), ref.path(), ref.section(), remaining);
            }
            remaining -= Math.min(text.length(), remaining);
            seen.add(key);
            documents.add(new KnowledgeNode(DOCUMENT_REF_PREFIX + ref.path() + "#" + ref.section(),
                KnowledgeChunkType.DESCRIPTION, text, KnowledgeSource.REVIEWED_DOCUMENT,
                section.get().lastModified()));
        }
        return documents;
    }

    private static String truncateAtParagraph(String text, int limit) {
        String head = text.substring(0, limit);
        int cut = Math.max(head.lastIndexOf("\n\n"), head.lastIndexOf('\n'));
        return (cut > limit / 2 ? head.substring(0, cut) : head).stripTrailing();
    }

    private static String nodeKey(String entityRef, KnowledgeChunkType chunkType) {
        return entityRef + "|" + chunkType.name();
    }

    private static List<ContextCategory> loadCategories(ResourceLoader resourceLoader, String fixturesLocation) {
        Resource resource = resourceLoader.getResource(fixturesLocation);
        try (InputStream in = resource.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            FixtureFile fixtureFile = mapper.readValue(in, FixtureFile.class);
            if (fixtureFile.categories() == null) {
                throw new IllegalStateException(
                    "AskContextBuilder: fixture file at " + fixturesLocation + " has no 'categories' field");
            }
            return fixtureFile.categories();
        } catch (IOException e) {
            throw new IllegalStateException(
                "AskContextBuilder: failed to load context fixtures from " + fixturesLocation, e);
        }
    }

    private record FixtureFile(List<ContextCategory> categories) {}

    record ContextCategory(String id, List<String> keywordPatterns, Integer boost,
                           List<ContextNodeRef> contextNodes, List<ContextDocumentRef> contextDocuments) {}

    record ContextNodeRef(String entityRef, KnowledgeChunkType chunkType) {}

    /** audience: "household" (any role) or "administrator" (default; ADMINISTRATOR only). */
    record ContextDocumentRef(String path, String section, String audience) {}

    /** A category with its patterns compiled once: whole word/phrase, optional plural s/es. */
    private static final class CompiledCategory {
        private final ContextCategory category;
        private final List<Pattern> patterns;
        private final List<Integer> wordCounts;

        CompiledCategory(ContextCategory category) {
            this.category = category;
            List<String> keywords = category.keywordPatterns() == null ? List.of() : category.keywordPatterns();
            this.patterns = keywords.stream()
                .map(k -> Pattern.compile("(?<![a-z0-9_])" + withEndings(k.toLowerCase(Locale.ROOT).strip())
                    + "(?![a-z0-9_])"))
                .toList();
            this.wordCounts = keywords.stream().map(k -> k.strip().split("\\s+").length).toList();
        }

        /** The keyword plus its allowed endings; a keyword ending in "e" also takes the e-dropping forms (-ing, -ation). */
        private static String withEndings(String keyword) {
            String base = Pattern.quote(keyword) + INFLECTION;
            if (keyword.length() > 3 && keyword.endsWith("e")) {
                return "(?:" + base + "|" + Pattern.quote(keyword.substring(0, keyword.length() - 1))
                    + "(?:ing|ings|ation|ations))";
            }
            return base;
        }

        ContextCategory category() {
            return category;
        }

        /** Summed word count of the distinct patterns that matched, plus the category boost when any did. */
        int score(String normalizedQuestion) {
            int score = 0;
            boolean matched = false;
            // Two keywords that differ only by an ending (ignore, ignored) both match the same words once
            // endings are accepted; the same span is worth one keyword, not two.
            Set<String> countedSpans = new HashSet<>();
            for (int i = 0; i < patterns.size(); i++) {
                Matcher m = patterns.get(i).matcher(normalizedQuestion);
                if (m.find()) {
                    matched = true;
                    if (countedSpans.add(m.start() + ":" + m.end())) {
                        score += wordCounts.get(i);
                    }
                }
            }
            return matched ? score + (category.boost() == null ? 0 : category.boost()) : 0;
        }
    }
}
