package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.Map;

/**
 * A stored OAuth credential pair (or a proprietary equivalent, e.g. Ring's
 * password-grant tokens). expiresAt is null when the platform doesn't
 * document one. extra carries platform-specific fields that aren't a token
 * at all -- Ring's hardware_id must travel with its token pair (Ring
 * rejects a refresh from an unrecognized hardware_id), so it rides here
 * rather than a second, disconnected storage call.
 */
public record OAuthCredential(String accessToken, String refreshToken, Instant expiresAt, Map<String, String> extra) {}
