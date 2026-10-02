package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pins which paths GoogleAuthInterceptor actually guards. */
class WebConfigTest {

    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> mapped() { return getInterceptors(); }
    }

    private static boolean gated(String path) {
        WebConfig config = new WebConfig(mock(GoogleAuthInterceptor.class));
        ReflectionTestUtils.setField(config, "googleAuthEnabled", true);
        ExposedRegistry registry = new ExposedRegistry();
        config.addInterceptors(registry);
        return registry.mapped().stream()
            .map(MappedInterceptor.class::cast)
            .anyMatch(m -> m.matches(path, new AntPathMatcher()));
    }

    // 2026-10-02 (W-27/W-28): HaServiceController turns requests into real
    // HA service calls (heater on/off), so it must never be anonymous.
    @Test
    void haServiceCallsRequireAuthentication() {
        assertThat(gated("/api/ha/services")).isTrue();
    }

    // D14: device reads stay open for glanceable/kiosk use -- this change
    // must not quietly re-gate them.
    @Test
    void deviceReadsStayOpenPerD14() {
        assertThat(gated("/api/devices")).isFalse();
    }
}
