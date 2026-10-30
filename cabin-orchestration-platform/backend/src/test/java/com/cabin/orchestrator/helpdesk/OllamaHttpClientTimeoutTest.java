package com.cabin.orchestrator.helpdesk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** W-118: an Ollama that accepts the connection and never answers degrades to the raw-facts fallback instead of hanging. */
class OllamaHttpClientTimeoutTest {

    @Test
    @Timeout(30)
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
    void defaultsAreBoundedAndBelowTheLimitsInFrontOfTheBackend() {
        assertThat(OllamaHttpClient.DEFAULT_CONNECT_TIMEOUT_SECONDS).isEqualTo(3);
        // nginx /api/ proxy_read_timeout 60 s and Cloudflare Tunnel 100 s: the fallback must arrive first.
        assertThat(OllamaHttpClient.DEFAULT_READ_TIMEOUT_SECONDS).isBetween(30, 59);
    }

    @Test
    @Timeout(30)
    void zeroOrNegativeFallsBackToTheDefaultInsteadOfMeaningUnbounded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer stalled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalled.createContext("/", exchange -> {
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        stalled.start();
        try {
            OllamaHttpClient client = new OllamaHttpClient();
            ReflectionTestUtils.setField(client, "connectTimeoutSeconds", 0);
            ReflectionTestUtils.setField(client, "readTimeoutSeconds", -1);
            client.applyTimeouts();
            org.springframework.web.client.RestTemplate rest = (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(client, "rest");
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                (org.springframework.http.client.SimpleClientHttpRequestFactory) rest.getRequestFactory();
            assertThat((int) ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3_000);
            assertThat((int) ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(OllamaHttpClient.DEFAULT_READ_TIMEOUT_SECONDS * 1_000);
        } finally {
            release.countDown();
            stalled.stop(0);
        }
    }
}
