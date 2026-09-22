package com.cabin.orchestrator.helpdesk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C1a: reads one heading-delimited section out of an allowlisted repository
 * Markdown file, so AskContextBuilder can supply reviewed documentation as
 * Ask context without ingesting it into knowledge_node (S3: the natural key
 * (entity_ref, chunk_type) cannot hold multiple sections per document, and
 * nothing here is ever persisted).
 *
 * <p>Deliberately narrow: only the paths in {@link #ALLOWED_PREFIXES} /
 * {@link #ALLOWED_FILES} are readable, resolved under one configured docs
 * root (docker-compose.m920q.yml mounts ../../docs at /app/docs read-only,
 * so a merged doc change reaches Ask without a backend rebuild). The eval
 * oracle files under docs/ai-assistant/corpus/ hold the expected answers to
 * the frozen questions and are intentionally outside the allowlist -- golden
 * answers are never source data.
 */
@Component
public class ReviewedDocumentSource {

    private static final Logger log = LoggerFactory.getLogger(ReviewedDocumentSource.class);

    static final List<String> ALLOWED_PREFIXES = List.of("ai-assistant/user-guide/");
    static final Set<String> ALLOWED_FILES = Set.of("ai-assistant/contributing.md");

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");

    private final Path root;
    private final Map<String, CachedDocument> cache = new ConcurrentHashMap<>();

    public ReviewedDocumentSource(@Value("${ask.context.docs-root:/app/docs}") String docsRoot) {
        this.root = Path.of(docsRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("ReviewedDocumentSource: docs root {} is not a directory -- Ask will run without reviewed "
                + "document context (is ../../docs mounted into the container?)", root);
        }
    }

    /** One section: its heading line plus everything up to the next heading of the same or a higher level. */
    public record DocumentSection(String path, String anchor, String heading, String text, Instant lastModified) {}

    static boolean isAllowed(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return false;
        if (relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.contains("..")
                || !relativePath.endsWith(".md")) {
            return false;
        }
        return ALLOWED_FILES.contains(relativePath)
            || ALLOWED_PREFIXES.stream().anyMatch(relativePath::startsWith);
    }

    public Optional<DocumentSection> section(String relativePath, String anchor) {
        if (!isAllowed(relativePath)) {
            log.warn("ReviewedDocumentSource: refusing non-allowlisted path '{}'", relativePath);
            return Optional.empty();
        }
        try {
            Path file = root.resolve(relativePath).normalize();
            if (!Files.isRegularFile(file)) {
                log.warn("ReviewedDocumentSource: {} not found under {}", relativePath, root);
                return Optional.empty();
            }
            if (!file.toRealPath().startsWith(root.toRealPath())) {
                log.warn("ReviewedDocumentSource: {} resolves outside the docs root", relativePath);
                return Optional.empty();
            }
            CachedDocument document = load(relativePath, file);
            ParsedSection parsed = document.sections().get(anchor);
            if (parsed == null) {
                log.warn("ReviewedDocumentSource: no section '#{}' in {}", anchor, relativePath);
                return Optional.empty();
            }
            return Optional.of(new DocumentSection(relativePath, anchor, parsed.heading(), parsed.text(),
                document.lastModified()));
        } catch (IOException e) {
            log.warn("ReviewedDocumentSource: could not read {}: {}", relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    private CachedDocument load(String relativePath, Path file) throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        long size = Files.size(file);
        CachedDocument cached = cache.get(relativePath);
        if (cached != null && cached.lastModified().equals(modified) && cached.size() == size) {
            return cached;
        }
        CachedDocument parsed = new CachedDocument(modified, size,
            parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
        cache.put(relativePath, parsed);
        return parsed;
    }

    /** GitHub-style heading anchor: lowercase, punctuation dropped, spaces to hyphens, repeats get -1, -2. */
    static String slug(String headingText) {
        String plain = MARKDOWN_LINK.matcher(headingText).replaceAll("$1")
            .replace("`", "").replace("*", "").trim().toLowerCase();
        StringBuilder out = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            } else if (c == ' ') {
                out.append('-');
            }
        }
        return out.toString();
    }

    static Map<String, ParsedSection> parse(List<String> lines) {
        record Start(int level, String heading, String anchor, int line) {}
        List<Start> starts = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            Matcher m = HEADING.matcher(line);
            if (!m.matches()) continue;
            String base = slug(m.group(2));
            int n = seen.merge(base, 1, Integer::sum) - 1;
            starts.add(new Start(m.group(1).length(), line.strip(), n == 0 ? base : base + "-" + n, i));
        }
        Map<String, ParsedSection> sections = new HashMap<>();
        for (int s = 0; s < starts.size(); s++) {
            Start start = starts.get(s);
            int end = lines.size();
            for (int t = s + 1; t < starts.size(); t++) {
                if (starts.get(t).level() <= start.level()) {
                    end = starts.get(t).line();
                    break;
                }
            }
            String text = String.join("\n", lines.subList(start.line(), end)).strip();
            sections.put(start.anchor(), new ParsedSection(start.heading(), text));
        }
        return sections;
    }

    record ParsedSection(String heading, String text) {}

    private record CachedDocument(Instant lastModified, long size, Map<String, ParsedSection> sections) {}
}
