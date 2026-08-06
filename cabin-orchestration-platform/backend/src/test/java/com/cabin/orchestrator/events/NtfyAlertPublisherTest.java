package com.cabin.orchestrator.events;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against a real local HTTP server (not ntfy.sh) that
 * NtfyAlertPublisher pushes exactly when it should: CRITICAL with a topic
 * configured, and nowhere else. publishIfCritical() fires the POST
 * asynchronously (deliberately, so it never blocks EventConsumer's poll
 * loop on network I/O), so "no push happened" is asserted by waiting out
 * a short window rather than by an immediate check.
 */
class NtfyAlertPublisherTest {

    private HttpServer server;
    private String baseUrl;
    private CountDownLatch requestReceived;
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        requestReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            requestReceived.countDown();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CabinEvent eventWithSeverity(String severity) {
        return new CabinEvent(UUID.randomUUID().toString(), "test-device", "TEST_EVENT",
            severity, Instant.now(), Map.of());
    }

    @Test
    void criticalEventWithTopicConfiguredTriggersPush() throws InterruptedException {
        NtfyAlertPublisher publisher = new NtfyAlertPublisher("my-topic", baseUrl);

        publisher.publishIfCritical(eventWithSeverity("CRITICAL"));

        assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPath.get()).isEqualTo("/my-topic");
        assertThat(receivedBody.get()).contains("test-device").contains("TEST_EVENT");
    }

    @Test
    void warnEventDoesNotTriggerPush() throws InterruptedException {
        NtfyAlertPublisher publisher = new NtfyAlertPublisher("my-topic", baseUrl);

        publisher.publishIfCritical(eventWithSeverity("WARN"));

        assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void infoEventDoesNotTriggerPush() throws InterruptedException {
        NtfyAlertPublisher publisher = new NtfyAlertPublisher("my-topic", baseUrl);

        publisher.publishIfCritical(eventWithSeverity("INFO"));

        assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void criticalEventWithNoTopicConfiguredDoesNotTriggerPush() throws InterruptedException {
        NtfyAlertPublisher publisher = new NtfyAlertPublisher("", baseUrl);

        publisher.publishIfCritical(eventWithSeverity("CRITICAL"));

        assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isFalse();
    }
}
