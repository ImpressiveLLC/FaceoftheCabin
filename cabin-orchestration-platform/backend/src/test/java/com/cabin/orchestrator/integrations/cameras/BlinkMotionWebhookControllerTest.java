package com.cabin.orchestrator.integrations.cameras;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * See BlinkMotionWebhookController's own javadoc for why this endpoint
 * exists: Blink's clip/motion API is a confirmed dead end for this
 * account, so a phone-side notification-listener automation calls this
 * instead of relying on blinkbridge's own (never-firing) motion detection.
 */
class BlinkMotionWebhookControllerTest {

    private BlinkLiveviewService liveviewService;
    private BlinkMotionWebhookController controller;

    @BeforeEach
    void setUp() {
        liveviewService = mock(BlinkLiveviewService.class);
        controller = new BlinkMotionWebhookController(liveviewService);
    }

    private HttpServletRequest requestWithHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/blink-motion");
        if (value != null) request.addHeader("X-Blink-Motion-Api-Key", value);
        return request;
    }

    @Test
    void refusesWithServiceUnavailableWhenNoApiKeyIsConfigured() {
        ReflectionTestUtils.setField(controller, "apiKey", "");

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"home_aldrich_front\"}", requestWithHeader("anything"));

        assertEquals(503, response.getStatusCode().value());
        verifyNoInteractions(liveviewService);
    }

    @Test
    void rejectsAMissingApiKeyHeader() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"home_aldrich_front\"}", requestWithHeader(null));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(liveviewService);
    }

    @Test
    void rejectsAWrongApiKeyHeader() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"home_aldrich_front\"}", requestWithHeader("wrong"));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(liveviewService);
    }

    @Test
    void rejectsWhenNeitherQueryParamNorBodyProvideACamera() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");

        ResponseEntity<?> response = controller.trigger(null, null, requestWithHeader("secret123"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(liveviewService);
    }

    @Test
    void rejectsMalformedJsonInTheBodyInsteadOfLettingSpringReturnA415() {
        // 2026-08-16: a real MacroDroid config left its own "Content type"
        // body setting unset, which caused Spring's normal @RequestBody
        // binding to reject the request with 415 before this controller's
        // code ever ran -- fixed by reading the body as a raw string and
        // parsing it here instead. This covers genuinely malformed JSON,
        // which must still surface as a clear 400, not a 500.
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");

        ResponseEntity<?> response = controller.trigger(null, "{not valid json", requestWithHeader("secret123"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(liveviewService);
    }

    @Test
    void rejectsACameraNotInTheBlinkCameraMap() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");
        when(liveviewService.blinkCameraMap()).thenReturn(Map.of("driveway", "Outdoor 4 - DHEE"));

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"not_a_real_camera\"}", requestWithHeader("secret123"));

        assertEquals(400, response.getStatusCode().value());
        verify(liveviewService, never()).start(any());
    }

    @Test
    void startsLiveviewForACameraNamedInTheJsonBody() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");
        when(liveviewService.blinkCameraMap()).thenReturn(Map.of("home_aldrich_front", "AldrichFront"));
        when(liveviewService.start("home_aldrich_front"))
            .thenReturn(new BlinkLiveviewService.Result(true, false, 200, "{}", null));

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"home_aldrich_front\"}", requestWithHeader("secret123"));

        assertEquals(200, response.getStatusCode().value());
        verify(liveviewService).start("home_aldrich_front");
    }

    @Test
    void startsLiveviewForACameraNamedInTheQueryParamWithNoBodyAtAll() {
        // Belt-and-suspenders path -- doesn't depend on the caller's body/
        // content-type handling being correct at all.
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");
        when(liveviewService.blinkCameraMap()).thenReturn(Map.of("home_aldrich_front", "AldrichFront"));
        when(liveviewService.start("home_aldrich_front"))
            .thenReturn(new BlinkLiveviewService.Result(true, false, 200, "{}", null));

        ResponseEntity<?> response = controller.trigger("home_aldrich_front", null, requestWithHeader("secret123"));

        assertEquals(200, response.getStatusCode().value());
        verify(liveviewService).start("home_aldrich_front");
    }

    @Test
    void surfacesABadGatewayWhenBlinkbridgeItselfFails() {
        ReflectionTestUtils.setField(controller, "apiKey", "secret123");
        when(liveviewService.blinkCameraMap()).thenReturn(Map.of("home_aldrich_front", "AldrichFront"));
        when(liveviewService.start("home_aldrich_front"))
            .thenReturn(new BlinkLiveviewService.Result(false, false, 0, null, "blinkbridge unreachable"));

        ResponseEntity<?> response = controller.trigger(null, "{\"camera\":\"home_aldrich_front\"}", requestWithHeader("secret123"));

        assertEquals(502, response.getStatusCode().value());
    }
}
