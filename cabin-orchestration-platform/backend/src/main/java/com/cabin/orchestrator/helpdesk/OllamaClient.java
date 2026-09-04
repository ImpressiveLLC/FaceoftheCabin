package com.cabin.orchestrator.helpdesk;

import java.util.Optional;

/** Talks to the Ollama container (Sprint 0, docker-compose.m920q.yml). Empty when unreachable -- never an exception the caller has to handle. */
public interface OllamaClient {
    Optional<String> generate(String prompt);

    /** On-demand version lookup for the platform-info panel. Default no-op keeps pre-existing test fakes compiling unchanged. */
    default Optional<String> fetchVersion() { return Optional.empty(); }
}
