package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused on the /api/rules/** carve-out added 2026-08-14 (external review
 * of the first live workflow test: an unauthenticated write endpoint that
 * can arm a real-actuator-firing rule is a materially bigger exposure than
 * a generic device-status GET). No real Google token is available in a
 * test run, so these assertions are about which requests reach the token
 * check at all, not about token validity itself -- that half is already
 * implicitly covered by every other gated endpoint using this same class.
 */
class GoogleAuthInterceptorTest {

    private final GoogleAuthInterceptor interceptor = new GoogleAuthInterceptor();

    @Test
    void getOnRulesWorkflowsPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus(), "no error should have been written for an allowed request");
    }

    @Test
    void getOnRulesExecutionsPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rules/workflows/wf-1/executions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void postOnRulesWorkflowsIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void postOnRulesFireIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rules/workflows/wf-1/fire");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void deleteOnRulesWorkflowsIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/rules/workflows/wf-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void optionsPreflightOnRulesPassesThroughRegardless() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void postOnAnUnrelatedOpenPathStillRequiresNoTokenCheckHere() throws Exception {
        // Sanity check that the new carve-out is scoped to /api/rules/** only --
        // this interceptor is never registered against /api/devices/** in
        // WebConfig, so this isn't testing THIS class's behavior on that path
        // so much as confirming the /api/rules/ prefix check isn't accidentally
        // matching something broader like /api/rulesengine/whatever.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rulesengine/not-actually-rules");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // No token set -- if the prefix check were too broad (e.g. "contains"
        // instead of startsWith(".../api/rules/")) this would incorrectly pass.
        ReflectionTestUtils.setField(interceptor, "expectedClientId", "");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed, "/api/rulesengine/... must not be treated as an /api/rules/... read");
    }
}
