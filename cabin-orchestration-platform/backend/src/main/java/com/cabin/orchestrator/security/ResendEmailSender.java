package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Real production sender via Resend's HTTP API (https://resend.com) --
 * chosen 2026-09-02 for Sprint 4 (Tier 2 managed users, passwordless
 * magic-link login): this app's email volume is a handful of invites ever,
 * well within Resend's free tier, and its API needs nothing more than a
 * single authenticated POST (no SMTP credentials/ports to manage).
 *
 * cabin.email.from defaults to Resend's own shared testing sender
 * (onboarding@resend.dev), which works immediately with no domain
 * verification -- swap it for a verified sender on your own domain once
 * set up in the Resend dashboard; nothing else about this class changes.
 *
 * A send failure surfaces as a RestClientException straight to the caller
 * (ManagedUserService.invite()) rather than being swallowed here -- an
 * admin issuing an invite needs to know it didn't actually go out, not see
 * a false "sent" confirmation.
 */
@Component
public class ResendEmailSender implements MagicLinkEmailSender {

    @Value("${cabin.email.resendApiKey:}")
    private String apiKey;

    @Value("${cabin.email.from:Cabin <onboarding@resend.dev>}")
    private String fromAddress;

    private final RestTemplate http = new RestTemplate();

    @Override
    public void sendMagicLink(String toEmail, String toName, String magicLinkUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cabin.email.resendApiKey is not configured -- cannot send invite emails");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
            "from", fromAddress,
            "to", List.of(toEmail),
            "subject", "Your cabin access link",
            "html", "<p>Hi " + escapeHtml(toName) + ",</p>"
                + "<p>Here's your sign-in link for the cabin app:</p>"
                + "<p><a href=\"" + magicLinkUrl + "\">" + magicLinkUrl + "</a></p>"
                + "<p>This link expires in 30 minutes and can only be used once.</p>");
        http.postForEntity("https://api.resend.com/emails", new HttpEntity<>(body, headers), Map.class);
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
