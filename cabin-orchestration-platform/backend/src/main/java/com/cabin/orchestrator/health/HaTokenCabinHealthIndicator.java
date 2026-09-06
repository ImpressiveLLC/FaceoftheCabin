package com.cabin.orchestrator.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Sprint 5 WSJF #1 (r7 handover) -- HA_TOKEN has gone silently blank in
 * production twice (2026-08-21, 2026-09-05), both times degrading Kidde/
 * Liebherr discovery for days with zero loud error anywhere. This
 * contributes to /actuator/health so a blank token turns the aggregate
 * status DOWN, which deploy-cabin-backend.yml's existing health-check step
 * (`curl -sf .../actuator/health | grep -q '"status":"UP"'`) already fails
 * closed on -- no new CI step needed, see that workflow's own comment
 * added alongside this class.
 *
 * @Value("${HA_TOKEN:}") deliberately mirrors HomeAssistantAdapter's own
 * binding, not the "cabin.homeassistant.token" nested YAML path -- see that
 * class's 2026-08-12 comment for why that nested path is dead config that
 * silently never matches what docker-compose.m920q.yml actually sets.
 */
@Component
public class HaTokenCabinHealthIndicator implements HealthIndicator {

    @Value("${HA_TOKEN:}")
    private String haToken;

    @Override
    public Health health() {
        if (haToken.isBlank()) {
            return Health.down()
                .withDetail("location", "cabin")
                .withDetail("reason", "HA_TOKEN blank or missing")
                .build();
        }
        return Health.up().withDetail("location", "cabin").build();
    }
}
