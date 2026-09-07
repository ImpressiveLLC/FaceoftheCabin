package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * C1a deterministic context injection: reads a keyword-pattern -> KnowledgeNode
 * map from docs/ai-assistant/rag/context-fixtures-r1.json (see that file's own
 * "description" field) at startup and, per question, injects a fixed set of
 * KnowledgeNodes instead of relying on TinyHelpdeskService's word-overlap
 * retrieval. Categories are checked in declaration order; first
 * keywordPattern substring match wins -- this mirrors the fixture file's own
 * documented selection rule, not an independent design choice made here.
 */
@Component
public class AskContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AskContextBuilder.class);

    private final KnowledgeNodeRepository knowledgeNodeRepository;
    private final List<ContextCategory> categories;

    public AskContextBuilder(
            KnowledgeNodeRepository knowledgeNodeRepository,
            ResourceLoader resourceLoader,
            @Value("${ask.context.fixtures:classpath:rag/context-fixtures-r1.json}") String fixturesLocation) {
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.categories = loadCategories(resourceLoader, fixturesLocation);
        log.info("AskContextBuilder loaded {} categories from {}", categories.size(), fixturesLocation);
    }

    public List<KnowledgeNode> buildContext(String question) {
        String lowered = question.toLowerCase();
        for (ContextCategory category : categories) {
            for (String pattern : category.keywordPatterns()) {
                if (lowered.contains(pattern)) {
                    return resolveNodes(category);
                }
            }
        }
        return List.of();
    }

    private List<KnowledgeNode> resolveNodes(ContextCategory category) {
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

    record ContextCategory(String id, List<String> keywordPatterns, List<ContextNodeRef> contextNodes) {}

    record ContextNodeRef(String entityRef, KnowledgeChunkType chunkType) {}
}
