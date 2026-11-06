package com.cabin.orchestrator.helpdesk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ranks reviewed guide sections against a question by plain word overlap, so a question whose wording no
 * context-map keyword anticipates can still reach the section that answers it.
 *
 * <p>Deliberately small and deterministic: lowercase word tokens, a short stop list, a crude suffix stem,
 * inverse-document-frequency weights computed over the candidate sections themselves, a heading-match bonus
 * and a saturating term-frequency term for the body. No model, no embedding, no state. Pure over its inputs,
 * so a given question and guide always rank the same way.
 *
 * <p>Of the best-scoring {@code limit} sections, only those sharing at least {@code minMatchedTerms} distinct
 * question words (in heading or body) are returned. Fewer shared words are as likely to be incidental ("now",
 * "right", "add") as relevant, and an incidental section handed to the model invites a confident answer from a
 * near miss. The gate is applied after the cut, not before, so a low-scoring section never takes a top slot
 * by sharing a few common words.
 */
final class GuideSectionRanker {

    /** Weight of a question word that appears in the section heading, relative to one in the body. */
    static final double HEADING_WEIGHT = 2.0;

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern HEADING_MARKS = Pattern.compile("^#{1,6}\\s+");
    private static final List<String> SUFFIXES =
        List.of("ations", "ation", "ings", "ing", "ers", "er", "ed", "es", "s");
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "be", "it", "its",
        "how", "do", "does", "can", "i", "my", "me", "we", "you", "your", "what", "when", "where", "why",
        "which", "with", "at", "by", "from", "as", "this", "that", "there", "if", "not", "no", "so", "but",
        "about", "into", "than", "then", "them", "they", "their", "our", "has", "have", "had", "will", "would",
        "should", "could", "any", "all");

    private GuideSectionRanker() {}

    /** A candidate section and its ranking score. */
    record Ranked(ReviewedDocumentSource.DocumentSection section, double score, int matchedTerms) {}

    /**
     * @param limit           most sections to return
     * @param minMatchedTerms distinct question words a section must share to be returned
     */
    static List<Ranked> rank(String question, List<ReviewedDocumentSource.DocumentSection> candidates,
                             int limit, int minMatchedTerms) {
        if (question == null || candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> questionTerms = new HashSet<>(stems(question));
        if (questionTerms.isEmpty()) {
            return List.of();
        }

        List<Indexed> index = new ArrayList<>(candidates.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (ReviewedDocumentSource.DocumentSection section : candidates) {
            Indexed indexed = new Indexed(section, counts(stems(HEADING_MARKS.matcher(section.heading())
                .replaceFirst(""))), counts(stems(section.text())));
            index.add(indexed);
            Set<String> present = new HashSet<>(indexed.heading().keySet());
            present.addAll(indexed.body().keySet());
            for (String term : present) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int n = index.size();
        List<Ranked> ranked = new ArrayList<>();
        for (Indexed indexed : index) {
            double score = 0;
            int matched = 0;
            int bodyLength = indexed.body().values().stream().mapToInt(Integer::intValue).sum() + 1;
            for (String term : questionTerms) {
                boolean inHeading = indexed.heading().containsKey(term);
                int bodyCount = indexed.body().getOrDefault(term, 0);
                if (!inHeading && bodyCount == 0) {
                    continue;
                }
                matched++;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                if (inHeading) {
                    score += HEADING_WEIGHT * idf;
                }
                if (bodyCount > 0) {
                    score += idf * (bodyCount * 2.2) / (bodyCount + 1.2 * (0.25 + 0.75 * bodyLength / 400.0));
                }
            }
            if (matched > 0) {
                ranked.add(new Ranked(indexed.section(), score, matched));
            }
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
            .thenComparing(r -> r.section().path() + "#" + r.section().anchor()));
        // The gate applies to the best-scoring sections only: a section that scores low overall is not promoted
        // just because it happens to share a few common words, so a question with no good match gets nothing.
        return ranked.subList(0, Math.min(limit, ranked.size())).stream()
            .filter(r -> r.matchedTerms() >= minMatchedTerms)
            .toList();
    }

    static List<String> stems(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            out.add(stem(word));
        }
        return out;
    }

    private static String stem(String word) {
        for (String suffix : SUFFIXES) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= 3) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    private static Map<String, Integer> counts(List<String> terms) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : terms) {
            counts.merge(term, 1, Integer::sum);
        }
        return counts;
    }

    private record Indexed(ReviewedDocumentSource.DocumentSection section, Map<String, Integer> heading,
                           Map<String, Integer> body) {}
}
