package com.cabin.orchestrator.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Companion to HaTokenCabinHealthIndicator for the home location. Deviates
 * from the r7 handover's literal ask (infer "not deployed yet" from a blank
 * HOME_HA_URL): docker-compose.m920q.yml sets
 * HOME_HA_URL=${HOME_HA_URL:-http://home-hub:8123}, so the container-side
 * env var is never actually blank -- it always resolves to that default,
 * which is also the real intended hostname once home IS deployed (see
 * CLAUDE.md's Tailscale hostname table). Blank-URL detection would either
 * never fire (false negative forever) or misfire once home is genuinely
 * live and still using that same hostname (false negative then too).
 *
 * HOME_HA_DEPLOYED (renamed from HOME_HUB_DEPLOYED, W-60 / D23 R-HD-6: it only
 * means "a separate Home Assistant exists at Home for cabin-backend to call",
 * not "Home is live"; the old name is still read for one release) is a new,
 * explicit, non-secret flag instead (default false, set true by Nate once a
 * Home Assistant at Home is actually connected) --
 * see infra/.env.m920q.example and docker-compose.m920q.yml's cabin-backend
 * environment block, which is an explicit allowlist and needed the new var
 * added there too, same lesson as this project's own Vaultwarden-wiring
 * history.
 *
 * OUT_OF_SERVICE (not deployed yet, not a failure) vs. DOWN (deployed but
 * misconfigured) is deliberate so the CI health check can fail only on a
 * real regression, never on an intentionally-unconfigured location --
 * requires management.endpoint.health.status.order in application.yml to
 * rank OUT_OF_SERVICE below UP, or a permanently-OUT_OF_SERVICE home
 * indicator would drag the aggregate status (and every future deploy)
 * down with it. See that config entry's own comment.
 */
@Component
public class HaTokenHomeHealthIndicator implements HealthIndicator {

    @Value("${HOME_HA_DEPLOYED:${HOME_HUB_DEPLOYED:false}}")
    private boolean homeHaDeployed;

    @Value("${HOME_HA_TOKEN:}")
    private String homeHaToken;

    @Override
    public Health health() {
        if (!homeHaDeployed) {
            return Health.outOfService()
                .withDetail("location", "home")
                .withDetail("reason", "no Home Assistant at Home (HOME_HA_DEPLOYED=false)")
                .build();
        }
        if (homeHaToken.isBlank()) {
            return Health.down()
                .withDetail("location", "home")
                .withDetail("reason", "HOME_HA_TOKEN blank or missing")
                .build();
        }
        return Health.up().withDetail("location", "home").build();
    }
}
