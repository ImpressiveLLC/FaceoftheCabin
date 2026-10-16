package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ollama {@code /api/chat} with a JSON Schema {@code format}, temperature 0. Same host config as OllamaHttpClient. */
@Component
public class OllamaChatClient implements IntentChatClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatClient.class);

    private final String ollamaUrl;
    private final RestTemplate rest;

    public OllamaChatClient(@Value("${cabin.ollama.url:http://ollama:11434}") String ollamaUrl,
                            @Value("${cabin.ask.intent.timeout-seconds:60}") int timeoutSeconds) {
        this.ollamaUrl = ollamaUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutSeconds * 1_000);
        this.rest = new RestTemplate(factory);
    }

    @Override
    public Optional<ChatResult> chat(String model, String system, String user, JsonNode format) {
        long started = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("stream", false);
            body.put("format", format);
            body.put("options", Map.of("temperature", 0));
            body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
            JsonNode response = rest.postForObject(ollamaUrl + "/api/chat", body, JsonNode.class);
            String content = response == null ? null : response.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.of(new ChatResult(model, content, (System.nanoTime() - started) / 1_000_000));
        } catch (Exception e) {
            log.warn("Ollama chat unavailable for model {}: {}", model, e.getMessage());
            return Optional.empty();
        }
    }
}
