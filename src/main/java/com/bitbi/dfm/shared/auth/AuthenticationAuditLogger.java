package com.bitbi.dfm.shared.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authentication failure handler that logs auth failures with structured MDC context.
 *
 * Logs authentication failures with the following structured fields:
 * - event: "auth_failure"
 * - timestamp: ISO-8601 (auto-added by logging framework)
 * - ip: Client IP address
 * - endpoint: Request URI
 * - method: HTTP method
 * - status: HTTP status code (401 or 403)
 * - tokenType: "jwt" or "keycloak" (detected from header)
 * - message: "Authentication failed"
 *
 * FR-013: Authentication audit logging
 */
@Component
public class AuthenticationAuditLogger implements AuthenticationFailureHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationAuditLogger.class);

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        try {
            // Add MDC context for structured logging
            MDC.put("ip", getClientIp(request));
            MDC.put("endpoint", request.getRequestURI());
            MDC.put("method", request.getMethod());
            MDC.put("status", String.valueOf(response.getStatus()));
            MDC.put("tokenType", detectTokenType(request));

            // Log the authentication failure
            logger.warn("auth_failure: Authentication failed for {} {} from {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    getClientIp(request));

        } finally {
            // Clear MDC context to prevent memory leaks
            MDC.clear();
        }
    }

    /**
     * Extract client IP address from request, considering X-Forwarded-For header.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For may contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Detect token type from request headers.
     *
     * Checks Authorization header for Bearer token pattern and X-Keycloak-Token header.
     * Returns "jwt", "keycloak", or "unknown".
     */
    private String detectTokenType(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        String keycloakTokenHeader = request.getHeader("X-Keycloak-Token");

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            // Heuristic: Keycloak tokens are typically longer (>500 chars)
            // JWT tokens from this system are shorter (<300 chars)
            String token = authorizationHeader.substring(7);
            if (token.length() > 500) {
                return "keycloak";
            }
            return "jwt";
        }

        if (keycloakTokenHeader != null) {
            return "keycloak";
        }

        return "unknown";
    }
}
