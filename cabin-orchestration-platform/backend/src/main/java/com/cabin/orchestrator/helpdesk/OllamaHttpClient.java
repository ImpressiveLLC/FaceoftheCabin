package com.cabin.orchestrator.helpdesk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestTemplate rest = new RestTemplate();

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
}
