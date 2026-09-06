package com.cabin.orchestrator.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HaTokenHomeHealthIndicatorTest {

    private static HaTokenHomeHealthIndicator indicatorWith(boolean deployed, String token) {
        HaTokenHomeHealthIndicator indicator = new HaTokenHomeHealthIndicator();
        ReflectionTestUtils.setField(indicator, "homeHubDeployed", deployed);
        ReflectionTestUtils.setField(indicator, "homeHaToken", token);
        return indicator;
    }

    @Test
    void reportsOutOfServiceWhenHomeHubIsNotDeployedRegardlessOfToken() {
        Health health = indicatorWith(false, "").health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
        assertEquals("home", health.getDetails().get("location"));
    }

    @Test
    void reportsOutOfServiceEvenIfATokenHappensToBePresent() {
        // A stale/leftover token value shouldn't flip this to UP -- the
        // deployed flag is the sole gate, per the class's own reasoning
        // about why blank-token/blank-URL inference is unreliable here.
        Health health = indicatorWith(false, "some-token").health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    }

    @Test
    void reportsDownWhenDeployedButTokenIsBlank() {
        Health health = indicatorWith(true, "").health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("home", health.getDetails().get("location"));
    }

    @Test
    void reportsUpWhenDeployedAndTokenIsPresent() {
        Health health = indicatorWith(true, "a-real-home-token").health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("home", health.getDetails().get("location"));
    }
}
