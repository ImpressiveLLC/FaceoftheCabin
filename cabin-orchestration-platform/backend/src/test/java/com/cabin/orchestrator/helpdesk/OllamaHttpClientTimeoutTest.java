package com.cabin.orchestrator.helpdesk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** W-118: an Ollama that accepts the connection and never answers degrades to the raw-facts fallback instead of hanging. */
class OllamaHttpClientTimeoutTest {

    @Test
    void aStalledOllamaReturnsEmptySoTheAskFallsBackToRawFacts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer stalled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalled.createContext("/", exchange -> {
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        stalled.start();
        try {
            OllamaHttpClient client = new OllamaHttpClient();
            ReflectionTestUtils.setField(client, "ollamaUrl", "http://127.0.0.1:" + stalled.getAddress().getPort());
            ReflectionTestUtils.setField(client, "model", "llama3.2:3b");
            ReflectionTestUtils.setField(client, "connectTimeoutSeconds", 1);
            ReflectionTestUtils.setField(client, "readTimeoutSeconds", 1);
            client.applyTimeouts();

            long started = System.nanoTime();
            Optional<String> answer = client.generate("hello");
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;

            assertThat(answer).isEmpty();
            assertThat(seconds).isLessThan(10);
        } finally {
            release.countDown();
            stalled.stop(0);
        }
    }

    @Test
    void defaultsAreBoundedAndWellAboveAColdGeneration() {
        OllamaHttpClient client = new OllamaHttpClient();
        assertThat((int) ReflectionTestUtils.getField(client, "connectTimeoutSeconds")).isEqualTo(3);
        assertThat((int) ReflectionTestUtils.getField(client, "readTimeoutSeconds")).isGreaterThanOrEqualTo(60);
    }
}
