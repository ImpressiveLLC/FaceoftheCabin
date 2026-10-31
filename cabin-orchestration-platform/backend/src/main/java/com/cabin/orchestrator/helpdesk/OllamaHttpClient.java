package com.cabin.orchestrator.helpdesk;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Same fallback-on-failure shape as DiscoveryServiceClient -- Ollama being
 * unreachable (not yet pulled, container restarting) degrades the Tiny
 * Helpdesk to raw retrieved facts (see TinyHelpdeskService), never a 500.
 * No host port on the ollama container itself (Sprint 0's own comment on
 * why) -- reached only via the docker network hostname.
 */
@Component
public class OllamaHttpClient implements OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaHttpClient.class);

    @Value("${cabin.ollama.url:http://ollama:11434}")
    private String ollamaUrl;

    @Value("${cabin.ollama.model:llama3.2:3b}")
    private String model;

    /** Connect and read bounds (W-118): an Ollama that accepts the connection and never answers must not hold the Ask open. */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 3;

    /**
     * Below the smallest limit in front of the backend (nginx's 60 s proxy_read_timeout on /api/, Cloudflare Tunnel's 100 s), so a
     * wedged Ollama ends in the raw-facts fallback, not a 504 or 524. Still above a cold 3B generation on the M920q CPU.
     */
    static final int DEFAULT_READ_TIMEOUT_SECONDS = 50;

    @Value("${cabin.ollama.connect-timeout-seconds:" + DEFAULT_CONNECT_TIMEOUT_SECONDS + "}")
    private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;

    @Value("${cabin.ollama.read-timeout-seconds:" + DEFAULT_READ_TIMEOUT_SECONDS + "}")
    private int readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;

    private RestTemplate rest = new RestTemplate(factory(DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS));

    /** Zero or negative would mean "no timeout" to the JDK client, which is the bug this class fixes, so it falls back to the default. */
    @PostConstruct
    void applyTimeouts() {
        rest = new RestTemplate(factory(
            connectTimeoutSeconds > 0 ? connectTimeoutSeconds : DEFAULT_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds > 0 ? readTimeoutSeconds : DEFAULT_READ_TIMEOUT_SECONDS));
    }

    /** Seconds to milliseconds, saturating instead of overflowing int for an absurdly large setting (W-127 item 5). */
    static int millis(int seconds) {
        return (int) Math.min(Integer.MAX_VALUE, seconds * 1_000L);
    }

    private static SimpleClientHttpRequestFactory factory(int connectSeconds, int readSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis(connectSeconds));
        factory.setReadTimeout(millis(readSeconds));
        return factory;
    }

    @Override
    public Optional<String> generate(String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            GenerateResponse response = rest.postForObject(ollamaUrl + "/api/generate", body, GenerateResponse.class);
            if (response == null || response.response == null || response.response.isBlank()) return Optional.empty();
            return Optional.of(response.response.trim());
        } catch (Exception e) {
            log.warn("Ollama unavailable, Tiny Helpdesk falling back to raw facts: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Minimal shape matching Ollama's own /api/generate response body. */
    private static class GenerateResponse {
        public String response;
    }

    @Override
    public Optional<String> fetchVersion() {
        try {
            VersionResponse response = rest.getForObject(ollamaUrl + "/api/version", VersionResponse.class);
            return (response == null || response.version == null) ? Optional.empty() : Optional.of(response.version);
        } catch (Exception e) {
            log.warn("Ollama version fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Minimal shape matching Ollama's own /api/version response body. */
    private static class VersionResponse {
        public String version;
    }
}
