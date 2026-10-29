package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;

/** Where a request really came from: Cloudflare's header, else the first X-Forwarded-For hop, else the socket. */
public final class AccessClient {
    private AccessClient() { }

    public static String ip(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
