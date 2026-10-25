package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * One structured-output chat call to the local model (Ollama {@code /api/chat} with a JSON Schema {@code format}).
 * Empty when the model is unreachable or answers with no content: callers fail closed, never throw.
 */
public interface IntentChatClient {

    record ChatResult(String model, String content, long latencyMillis) {}

    Optional<ChatResult> chat(String model, String system, String user, JsonNode format);
}
