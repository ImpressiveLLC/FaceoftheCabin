package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api/notes, /api/chores, /api/profiles, /api/camera, /api/schedule,
 * /api/rules (writes only — see GoogleAuthInterceptor's GET carve-out, added
 * 2026-08-14), (PATCH only, see GoogleAuthInterceptor) /api/tech-id/findings,
 * and — added 2026-09-01 — /api/events and /api/alerts (every method,
 * including GET, no carve-out) require a Google token.
 *
 * The events/alerts addition closes a real, confirmed-live exposure: a
 * security audit found both returning full unauthenticated responses
 * (real-time device telemetry, active security alerts, and — for
 * main_water_valve specifically — public confirmation that the safety-
 * critical water shutoff was currently offline) to the open internet.
 * Unlike /api/rules's read side, there's no "read can't fire a physical
 * action so it's safe public" argument here — the data itself (occupancy-
 * adjacent telemetry, live alarm/alert state) is the thing worth
 * protecting, not just write access. cabin-ui's own fetch calls to these
 * endpoints never sent a token before this change (confirmed by direct
 * grep) — see App.jsx's authedFetch threading, added in the same change,
 * for the frontend half of this fix.
 *
 * /api/devices — added 2026-09-01, same day as events/alerts above.
 * Previously left open deliberately (room names, vendor/model, and
 * capabilities aren't safety-sensitive the way an active alert is), but a
 * live check confirmed it discloses a full physical device inventory
 * (room "Mech Room", vendor "Tuya", model "TS0001", etc.) to anonymous
 * internet callers — the same "reveals the physical layout of an
 * unoccupied cabin" category of concern as events/alerts, just one notch
 * less urgent. Gated the same way, no partial/sanitized-projection carve-
 * out — see App.jsx's authedFetch threading for the ~20 call sites this
 * touched. A guest-access model (share links, then passwordless managed
 * users) is planned separately for parties without a Google account
 * (e.g. an insurance adjuster) — see the plan doc; this endpoint will
 * gain a second, non-Google auth path once that lands, not a rollback of
 * this gate.
 *
 * dashboard config stays open, matching how it already worked before this
 * interceptor existed — that decision is unchanged.
 *
 * cabin.security.googleAuth.enabled defaults to true (secure by default —
 * absence of the property changes nothing). The only reason it exists is
 * local verification: there's no way to obtain a real Google access token
 * in an automated/offline test run, so integration testing this endpoint
 * needs a way to turn the gate off. Deliberately not referenced in any
 * shipped compose/.env file — set it only as an ad-hoc local override.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final GoogleAuthInterceptor authInterceptor;

    @Value("${cabin.security.googleAuth.enabled:true}")
    private boolean googleAuthEnabled;

    public WebConfig(GoogleAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!googleAuthEnabled) return;
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/notes/**", "/api/chores/**", "/api/profiles/**", "/api/camera/**",
                "/api/tech-id/findings/**", "/api/rules/**", "/api/schedule/**",
                "/api/events/**", "/api/alerts/**", "/api/devices/**");
    }
}
