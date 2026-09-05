package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api/notes, /api/chores, /api/profiles, /api/camera, /api/schedule,
 * /api/rules (writes only — see GoogleAuthInterceptor's GET carve-out, added
 * 2026-08-14), (PATCH only, see GoogleAuthInterceptor) /api/tech-id/findings,
 * and /api/events (GET .../telemetry-history and .../reported-fields stay
 * open — see GoogleAuthInterceptor's carve-out, added 2026-09-04; every
 * other method/path, including the bare GET collection, requires a token).
 *
 * The events gate closes a real, confirmed-live exposure: a security audit
 * found it returning full unauthenticated responses (camera/motion event
 * replay — the specific "is anyone home" signal) to the open internet.
 * cabin-ui's own fetch calls to this endpoint never sent a token before
 * that change (confirmed by direct grep) — see App.jsx's authedFetch
 * threading, added in the same change, for the frontend half of this fix.
 * telemetry-history/reported-fields don't carry that signal at all (numeric
 * sensor history, not a camera/motion log) and were only ever gated by
 * sharing the /api/events/** prefix — carving them out fixed a real,
 * reported regression: SensorHistoryPanel's charting/dropdowns/history
 * silently rendered nothing for a not-signed-in caller.
 *
 * /api/devices and /api/alerts — gated 2026-09-01, ungated again 2026-09-04
 * after direct user pushback: this app's whole purpose is glanceable,
 * zero-friction status (a kiosk/wall display can't stop and OAuth every
 * time someone looks at it), and neither endpoint's content actually maps
 * onto the threat this project cares about (a physical break-in, which
 * doesn't depend on whether someone could also see the humidity reading
 * online). Device inventory (room names, vendor/model, capabilities) and
 * alert state (leak/CO/battery) don't reveal whether anyone is home right
 * now the way camera/motion events do -- that's the one signal worth
 * protecting, and it's what /api/events above still gates. Do not re-add
 * these two without the same explicit conversation this reversal came from
 * -- see the shared "Cabin Platform Decisions" ontology artifact (D14) for
 * the full reasoning either way.
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
 * /api/platform-import — added 2026-09-03 (WSJF #9). GET .../records is
 * ADMINISTRATOR/ADULT_HOUSEHOLD_MEMBER (read-only, no live platform call);
 * everything else (proposals, confirm) is ADMINISTRATOR only, same
 * per-route pattern as /api/cross-domain above — see PlatformImportController.
 *
 * dashboard config stays open, matching how it already worked before this
 * interceptor existed — that decision is unchanged.
 *
 * /api/auth/session — added 2026-09-04, the CabinSession exchange endpoint
 * (see AuthController and CabinSession's own doc). Gated on purpose: this
 * is where "prove who you are" turns into "stay recognized for 30 days,"
 * so it has to go through the exact same check as everything else here --
 * the interceptor's own REQUEST_ATTR_EMAIL, set by whichever of the three
 * paths (Google token, managed session, or an existing CabinSession due
 * for renewal) actually authenticated the caller. /api/auth/session/revoke
 * is deliberately NOT in this list -- signing out only requires holding
 * the token you want to invalidate, the same "no extra proof needed to
 * destroy your own credential" contract a logout endpoint always has.
 *
 * /api/presence and /api/security — gated 2026-09-05, closing a tension
 * flagged during D14's own implementation: neither had ever been gated
 * (confirmed directly against this list), yet D14's own principle -- does
 * this endpoint reveal occupancy -- applies to both more directly than to
 * almost anything else in this file. /api/presence's GET returns the live
 * at-home/at-cabin/away profile and per-person signals; its PUT is a write
 * regardless. /api/security's GET returns armed/disarmed per location,
 * which for "armed_away" specifically implies nobody's home. Unlike
 * /api/devices/alerts above, there's no non-occupancy sub-path here worth
 * carving out -- the entire endpoint is the signal D14 says is worth
 * protecting, so both are fully gated, no GET carve-out. See the shared
 * ontology artifact's D14 section for the "Unresolved D14 Tensions" pin
 * this resolves.
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
                "/api/events/**", "/api/access-tokens/**",
                "/api/managed-users/**", "/api/kb/**", "/api/cross-domain/**", "/api/helpdesk/**",
                "/api/platform-import/**", "/api/system/platform-info", "/api/auth/session",
                "/api/presence/**", "/api/security/**");
    }
}
