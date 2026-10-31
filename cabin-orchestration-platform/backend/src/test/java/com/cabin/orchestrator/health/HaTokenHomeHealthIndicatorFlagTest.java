package com.cabin.orchestrator.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** W-60: HOME_HA_DEPLOYED is the flag; the old HOME_HUB_DEPLOYED is still read for one release; unset means not deployed. */
class HaTokenHomeHealthIndicatorFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withBean(HaTokenHomeHealthIndicator.class);

    @Test
    void unsetMeansNoHomeHaAndReportsOutOfService() {
        runner.run(ctx -> assertThat(ctx.getBean(HaTokenHomeHealthIndicator.class).health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE));
    }

    @Test
    void theNewNameTurnsItOn() {
        runner.withPropertyValues("HOME_HA_DEPLOYED=true", "HOME_HA_TOKEN=")
            .run(ctx -> assertThat(ctx.getBean(HaTokenHomeHealthIndicator.class).health().getStatus()).isNotEqualTo(Status.OUT_OF_SERVICE));
    }

    @Test
    void theOldNameIsStillReadAndTheNewNameWinsWhenBothAreSet() {
        runner.withPropertyValues("HOME_HUB_DEPLOYED=true", "HOME_HA_TOKEN=")
            .run(ctx -> assertThat(ctx.getBean(HaTokenHomeHealthIndicator.class).health().getStatus()).isNotEqualTo(Status.OUT_OF_SERVICE));
        runner.withPropertyValues("HOME_HUB_DEPLOYED=true", "HOME_HA_DEPLOYED=false")
            .run(ctx -> assertThat(ctx.getBean(HaTokenHomeHealthIndicator.class).health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE));
    }
}
