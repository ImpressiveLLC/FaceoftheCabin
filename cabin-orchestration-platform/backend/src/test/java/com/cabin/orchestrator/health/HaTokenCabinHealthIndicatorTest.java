package com.cabin.orchestrator.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HaTokenCabinHealthIndicatorTest {

    @Test
    void reportsDownWhenTokenIsBlank() {
        HaTokenCabinHealthIndicator indicator = new HaTokenCabinHealthIndicator();
        ReflectionTestUtils.setField(indicator, "haToken", "");

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("cabin", health.getDetails().get("location"));
    }

    @Test
    void reportsDownWhenTokenIsNull() {
        HaTokenCabinHealthIndicator indicator = new HaTokenCabinHealthIndicator();
        ReflectionTestUtils.setField(indicator, "haToken", "   ");

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void reportsUpWhenTokenIsPresent() {
        HaTokenCabinHealthIndicator indicator = new HaTokenCabinHealthIndicator();
        ReflectionTestUtils.setField(indicator, "haToken", "a-real-token-value");

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("cabin", health.getDetails().get("location"));
    }
}
