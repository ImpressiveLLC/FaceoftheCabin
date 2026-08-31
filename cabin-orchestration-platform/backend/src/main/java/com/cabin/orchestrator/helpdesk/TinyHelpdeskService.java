package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sprint 2 (docs/ontology/SPRINT-STATUS.md): the Tiny Helpdesk chat
 * endpoint. Retrieval is deliberately a plain word-overlap match over
 * KnowledgeNodeRepository.loadAll(), not an embedding/vector search --
 * KnowledgeNode's own doc comment already flags embedding_ref as a
 * "future hook," and this platform has ~80 KnowledgeNodes total (one per
 * in-scope device), not thousands. A real vector search is premature
 * infrastructure at this scale; word overlap is honest, correct, and
 * cheap here. Revisit if/when the corpus size actually justifies it.
 *
 * Ollama unreachable degrades to raw retrieved facts, never a 500 or a
 * silent empty answer -- answeredByModel tells the caller which
 * happened.
 */
@Service
public class TinyHelpdeskService {

    private static final int MAX_CONTEXT_NODES = 5;
    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "what", "which", "who",
        "where", "when", "why", "how", "does", "do", "did", "this", "that",
        "for", "and", "with", "about", "can", "you", "tell", "me");

    private final KnowledgeNodeRepository knowledgeNodeRepository;
    private final OllamaClient ollamaClient;

    public TinyHelpdeskService(KnowledgeNodeRepository knowledgeNodeRepository, OllamaClient ollamaClient) {
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.ollamaClient = ollamaClient;
    }

    public TinyHelpdeskAnswer ask(String question) {
        List<KnowledgeNode> relevant = retrieveRelevant(question);
        if (relevant.isEmpty()) {
            return new TinyHelpdeskAnswer(question,
                "I don't have any information about that yet.", List.of(), false);
        }

        String prompt = buildPrompt(question, relevant);
        Optional<String> modelAnswer = ollamaClient.generate(prompt);
        if (modelAnswer.isPresent()) {
            return new TinyHelpdeskAnswer(question, modelAnswer.get(), relevant, true);
        }

        String fallback = relevant.stream().map(KnowledgeNode::content).collect(Collectors.joining(" "));
        return new TinyHelpdeskAnswer(question, fallback, relevant, false);
    }

    private List<KnowledgeNode> retrieveRelevant(String question) {
        Set<String> queryWords = wordsOf(question);
        if (queryWords.isEmpty()) return List.of();
        return knowledgeNodeRepository.loadAll().stream()
            .map(node -> Map.entry(node, overlapScore(queryWords, node)))
            .filter(entry -> entry.getValue() > 0)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(MAX_CONTEXT_NODES)
            .map(Map.Entry::getKey)
            .toList();
    }

    private static int overlapScore(Set<String> queryWords, KnowledgeNode node) {
        Set<String> nodeWords = wordsOf(node.entityRef() + " " + node.content());
        int score = 0;
        for (String word : queryWords) {
            if (nodeWords.contains(word)) score++;
        }
        return score;
    }

    private static Set<String> wordsOf(String text) {
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9]+", " ").split("\\s+"))
            .filter(word -> word.length() > 2 && !STOPWORDS.contains(word))
            .collect(Collectors.toSet());
    }

    private static String buildPrompt(String question, List<KnowledgeNode> context) {
        StringBuilder sb = new StringBuilder(
            "You are a helpdesk assistant for a cabin home-automation system. "
            + "Answer the question using ONLY the facts below. If the facts don't "
            + "answer the question, say you don't know -- never guess.\n\nFacts:\n");
        for (KnowledgeNode node : context) {
            sb.append("- ").append(node.content()).append('\n');
        }
        sb.append("\nQuestion: ").append(question).append("\nAnswer:");
        return sb.toString();
    }
}
