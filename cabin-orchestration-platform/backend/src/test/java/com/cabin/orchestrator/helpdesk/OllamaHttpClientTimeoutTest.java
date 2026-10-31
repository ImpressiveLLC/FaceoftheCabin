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
            try { release.await(8, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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
            // The stub releases at 8 s; the client bound is 1 s. Under 5 s proves the client gave up on its own bound, so an
            // unbounded client (which only returns when the stub lets go at 8 s) fails here.
            assertThat(seconds).isLessThan(5);
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

    @Test
    void secondsToMillisecondsSaturatesInsteadOfOverflowing() {
        assertThat(OllamaHttpClient.millis(50)).isEqualTo(50_000);
        assertThat(OllamaHttpClient.millis(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(OllamaHttpClient.millis(3_000_000)).isEqualTo(Integer.MAX_VALUE);
    }

    /** The compose defaults repeat the Java constants; they must agree (W-127 item 4). */
    @Test
    void theComposeDefaultsAgreeWithTheJavaConstants() throws Exception {
        java.nio.file.Path compose = java.nio.file.Path.of("../infra/docker-compose.m920q.yml");
        if (!java.nio.file.Files.exists(compose)) compose = java.nio.file.Path.of("cabin-orchestration-platform/infra/docker-compose.m920q.yml");
        String text = java.nio.file.Files.readString(compose);
        java.util.regex.Matcher connect = java.util.regex.Pattern.compile("CABIN_OLLAMA_CONNECT_TIMEOUT_SECONDS: \\$\\{CABIN_OLLAMA_CONNECT_TIMEOUT_SECONDS:-(\\d+)\\}").matcher(text);
        java.util.regex.Matcher read = java.util.regex.Pattern.compile("CABIN_OLLAMA_READ_TIMEOUT_SECONDS: \\$\\{CABIN_OLLAMA_READ_TIMEOUT_SECONDS:-(\\d+)\\}").matcher(text);
        assertThat(connect.find()).as("connect default present in compose").isTrue();
        assertThat(read.find()).as("read default present in compose").isTrue();
        assertThat(Integer.parseInt(connect.group(1))).isEqualTo(OllamaHttpClient.DEFAULT_CONNECT_TIMEOUT_SECONDS);
        assertThat(Integer.parseInt(read.group(1))).isEqualTo(OllamaHttpClient.DEFAULT_READ_TIMEOUT_SECONDS);
    }
}
