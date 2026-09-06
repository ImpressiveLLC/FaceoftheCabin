package com.cabin.orchestrator.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the real Spring Boot aggregation behavior these two indicators
 * depend on -- not just each indicator's own health() method in isolation
 * (see HaTokenCabinHealthIndicatorTest/HaTokenHomeHealthIndicatorTest for
 * that), but that application.yml's
 * management.endpoint.health.status.order actually does what its own
 * comment claims: without it, Boot's default order ranks OUT_OF_SERVICE
 * above UP, so a permanently-OUT_OF_SERVICE home indicator would drag
 * every deploy's aggregate status down forever. Uses ApplicationContextRunner
 * (no Postgres/Docker needed -- this test boots only Health*AutoConfiguration
 * plus the two indicator beans, not the full app).
 */
class HealthAggregationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            HealthContributorAutoConfiguration.class,
            HealthEndpointAutoConfiguration.class))
        .withUserConfiguration(HaTokenCabinHealthIndicator.class, HaTokenHomeHealthIndicator.class);

    @Test
    void aggregateIsUpWhenCabinTokenIsPresentAndHomeIsPermanentlyOutOfService() {
        contextRunner
            .withPropertyValues(
                "HA_TOKEN=a-real-token",
                "HOME_HUB_DEPLOYED=false",
                "management.endpoint.health.status.order=down,up,out-of-service,unknown")
            .run(context -> {
                HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
                assertEquals(Status.UP, endpoint.health().getStatus(),
                    "home's permanent OUT_OF_SERVICE must not drag the aggregate down once ranked below UP");
            });
    }

    @Test
    void aggregateFallsBackToBootsDefaultOrderWithoutTheConfigOverride() {
        // Same inputs as above, minus the status.order property -- proves
        // the override in application.yml is load-bearing, not redundant
        // with some other default that would've made this fine anyway.
        contextRunner
            .withPropertyValues(
                "HA_TOKEN=a-real-token",
                "HOME_HUB_DEPLOYED=false")
            .run(context -> {
                HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
                assertEquals(Status.OUT_OF_SERVICE, endpoint.health().getStatus(),
                    "Boot's default status order ranks OUT_OF_SERVICE above UP -- this is exactly the failure mode application.yml's order override exists to prevent");
            });
    }

    @Test
    void aggregateIsDownWhenCabinTokenIsBlank() {
        contextRunner
            .withPropertyValues(
                "HA_TOKEN=",
                "HOME_HUB_DEPLOYED=false",
                "management.endpoint.health.status.order=down,up,out-of-service,unknown")
            .run(context -> {
                HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
                assertEquals(Status.DOWN, endpoint.health().getStatus(),
                    "a real regression (blank HA_TOKEN) must still win over the order override, not get masked by it");
            });
    }
}
