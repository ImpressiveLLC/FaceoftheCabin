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
 * /api/access-tokens — added 2026-09-01, admin-only management (create/
 * list/revoke) of Tier 1 guest share links. See GoogleAuthInterceptor's
 * own guest-token path (SCOPE_PATH_PREFIXES) for the other half of this:
 * a valid, unexpired, unrevoked share link is a genuine alternative
 * credential on /api/devices and /api/alerts (GET only, scope-limited),
 * checked before the Google-token requirement -- but creating/listing/
 * revoking links itself is never reachable with a guest token, only a
 * real signed-in admin.
 *
 * /api/managed-users — added 2026-09-02, Sprint 4 (Tier 2 managed users).
 * Same pattern as access-tokens above: admin CRUD (list/create/deactivate/
 * reactivate/invite) requires a real Google token. The one exception is
 * .../magic/{token}/consume, which GoogleAuthInterceptor itself exempts
 * via an exact-prefix carve-out (a managed user has no Google account by
 * definition, so the endpoint that establishes their session can't
 * require one) -- see that class's own comment. A valid managed-user
 * session (Authorization: ManagedSession {token}) is a genuine alternative
 * credential everywhere else this interceptor gates, read-only for VIEWER
 * and full read/write for HOUSEHOLD_MEMBER.
 *
 * /api/kb — added 2026-09-02, found while correcting a stale KnowledgeNode:
 * POST .../curate (writes MANUALLY_CURATED content) and .../regenerate had
 * no gate at all, reachable from the open internet. GET .../nodes stays
 * open, same GET-only carve-out pattern as /api/rules/** above.
 *
 * /api/cross-domain — added 2026-09-03, D11's authorization-model hard gate
 * (WSJF #6). Google-gated same as everything else here, but this is the
 * first route with an ADDITIONAL per-route role check on top of "is this
 * caller authenticated at all" — see CrossDomainController and
 * GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE.
 *
 * /api/helpdesk — added 2026-09-03 (WSJF #8). Was previously ungated
 * entirely; required now so REQUEST_ATTR_HOUSEHOLD_ROLE actually gets
 * resolved for a real signed-in caller at all — without this, a Tiny
 * Helpdesk question could never distinguish an administrator from anyone
 * else, making the CREDENTIAL_POINTER role gate in TinyHelpdeskService
 * unreachable rather than merely permissive. Any authenticated caller
 * (Google token, managed session, or Tier 1 guest token) may ask a
 * question — the household-role-specific redaction happens inside the
 * answer itself, not at this gate.
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
                "/api/events/**", "/api/alerts/**", "/api/devices/**", "/api/access-tokens/**",
                "/api/managed-users/**", "/api/kb/**", "/api/cross-domain/**", "/api/helpdesk/**");
    }
}
