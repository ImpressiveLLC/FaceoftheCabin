package com.cabin.orchestrator.security;

/**
 * Kept as its own interface, separate from ResendEmailSender, purely so
 * tests never need a real API key or network call -- same reasoning as
 * ProtocolAdapter/EventPublisher's own recording-fake pattern elsewhere in
 * this codebase's tests.
 */
public interface MagicLinkEmailSender {
    void sendMagicLink(String toEmail, String toName, String magicLinkUrl);
}
