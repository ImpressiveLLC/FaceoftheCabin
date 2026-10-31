package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The per-route administrator check for account and access management, so the code matches the documented rule
 * ("signed-in admin only", CLAUDE.md). GoogleAuthInterceptor already refuses a managed-user session on these prefixes and
 * demo links are denied by DemoAccessPolicy, but a signed-in Google or CabinSession caller who is not an ADMINISTRATOR
 * (an adult household member, say) passed the interceptor and reached these controllers with no role check of their own.
 * Same pattern as StorageTrimController, which answers 403 for anyone else.
 *
 * <p>The single-use magic-link consume route is the one exemption: the one-time token is its credential.
 */
final class AdministratorOnly {

    static final String MESSAGE = "Only an administrator can manage access and accounts";

    private AdministratorOnly() {}

    static void require(HttpServletRequest request) {
        Object role = request == null ? null : request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MESSAGE);
        }
    }
}
