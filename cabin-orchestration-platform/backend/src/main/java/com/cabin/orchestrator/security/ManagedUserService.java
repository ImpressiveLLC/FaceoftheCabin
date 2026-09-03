package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 2 managed users -- see ManagedUser's own doc and the plan's "Guest
 * Access Model" section. Orchestrates the whole passwordless flow: admin
 * enrolls a person, invite() emails them a short-lived magic link,
 * consumeMagicLink() exchanges a clicked link for a real, standing session
 * (never a proxy of the admin's own session), and validateSession() is
 * what GoogleAuthInterceptor calls on every request carrying one.
 */
@Service
public class ManagedUserService {

    /** Long enough to check email and click, short enough to limit exposure if intercepted. */
    private static final Duration MAGIC_LINK_TTL = Duration.ofMinutes(30);
    /** Generous on purpose -- a managed user shouldn't need to re-request a link often; revoke() is the real "end this session" lever. */
    private static final Duration SESSION_TTL = Duration.ofDays(90);

    private final ManagedUserStore users;
    private final MagicLinkTokenStore magicLinks;
    private final ManagedUserSessionStore sessions;
    private final MagicLinkEmailSender emailSender;

    // Read server-side, never trusted from a request -- an admin-controlled
    // config value, not something a caller could steer to build a
    // credential-carrying link pointing anywhere else (open-redirect-style risk).
    @Value("${cabin.frontend.origin:http://localhost:5173}")
    private String frontendOrigin;

    public ManagedUserService(ManagedUserStore users, MagicLinkTokenStore magicLinks,
                               ManagedUserSessionStore sessions, MagicLinkEmailSender emailSender) {
        this.users = users;
        this.magicLinks = magicLinks;
        this.sessions = sessions;
        this.emailSender = emailSender;
    }

    public ManagedUser create(String email, String name, ManagedUserRole role, String createdBy) {
        ManagedUser user = new ManagedUser(UUID.randomUUID().toString(), email, name, role, true, createdBy, Instant.now());
        users.save(user);
        return user;
    }

    public List<ManagedUser> list() {
        return users.loadAll();
    }

    public void setActive(String id, boolean active) {
        ManagedUser existing = users.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Managed user not found: " + id));
        users.save(new ManagedUser(existing.id(), existing.email(), existing.name(), existing.role(), active,
            existing.createdBy(), existing.createdAt()));
    }

    /** Generates and emails a fresh magic link, pointing at this instance's own configured frontend origin (cabin.frontend.origin). */
    public void invite(String managedUserId) {
        ManagedUser user = users.findById(managedUserId)
            .orElseThrow(() -> new java.util.NoSuchElementException("Managed user not found: " + managedUserId));
        if (!user.active()) {
            throw new IllegalStateException("Cannot invite a deactivated managed user -- reactivate them first");
        }
        Instant now = Instant.now();
        MagicLinkToken link = new MagicLinkToken(UUID.randomUUID().toString(), user.id(), now.plus(MAGIC_LINK_TTL), null, now);
        magicLinks.save(link);
        String url = frontendOrigin.replaceAll("/$", "") + "/auth/magic/" + link.token();
        emailSender.sendMagicLink(user.email(), user.name(), url);
    }

    /** Empty if the token is missing/expired/already used/points at a deactivated user; otherwise the newly-issued session. */
    public Optional<ManagedUserSession> consumeMagicLink(String rawToken) {
        Optional<MagicLinkToken> linkOpt = magicLinks.findByToken(rawToken).filter(t -> t.isValid(Instant.now()));
        if (linkOpt.isEmpty()) return Optional.empty();
        MagicLinkToken link = linkOpt.get();
        Optional<ManagedUser> userOpt = users.findById(link.managedUserId()).filter(ManagedUser::active);
        if (userOpt.isEmpty()) return Optional.empty();

        Instant now = Instant.now();
        magicLinks.markConsumed(rawToken, now);
        ManagedUserSession session = new ManagedUserSession(
            UUID.randomUUID().toString(), userOpt.get().id(), now.plus(SESSION_TTL), null, now);
        sessions.save(session);
        return Optional.of(session);
    }

    /** Empty unless the session is genuinely active right now AND its managed user is still active -- a deactivated user's existing sessions stop working immediately, not just future invites. */
    public Optional<ManagedUser> validateSession(String rawSessionToken) {
        return sessions.findByToken(rawSessionToken)
            .filter(s -> s.isValid(Instant.now()))
            .flatMap(s -> users.findById(s.managedUserId()))
            .filter(ManagedUser::active);
    }

    public void revokeSession(String rawSessionToken) {
        sessions.revoke(rawSessionToken, Instant.now());
    }
}
