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
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * - tokenType: "jwt" or "auth0" (derived from the token itself)
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
     * Detect token type by inspecting the bearer token itself.
     *
     * Deliberately derived from the token and nothing else: detection used to short-circuit on an
     * X-Keycloak-Token request header, which any caller can set, so the type recorded in the audit
     * trail was caller-controlled.
     *
     * Returns "jwt", "auth0", or "unknown". Visible for testing.
     */
    String detectTokenType(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");

        // Parse Bearer token if present
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7);
            return analyzeJwtStructure(token);
        }

        return "unknown";
    }

    /**
     * Analyze JWT token structure to determine its type.
     *
     * Decodes the JWT header and inspects the algorithm: the client API signs its own tokens with
     * HMAC, while Auth0 signs with RSA.
     *
     * @param token JWT token string
     * @return "jwt" for custom tokens, "auth0" for Auth0 tokens, "unknown" if unable to parse
     */
    private String analyzeJwtStructure(String token) {
        try {
            // JWT format: header.payload.signature
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return "unknown";
            }

            // Decode header (first part) - Base64URL encoded
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode header = mapper.readTree(headerJson);

            // Check algorithm field
            String algorithm = header.has("alg") ? header.get("alg").asText() : null;

            if (algorithm != null) {
                // HMAC algorithms (HS256, HS384, HS512) = custom JWT
                if (algorithm.startsWith("HS")) {
                    return "jwt";
                }
                // RSA algorithms (RS256, RS384, RS512) = Auth0
                if (algorithm.startsWith("RS")) {
                    return "auth0";
                }
            }

            // Parsable JWT signed with something else — treat as the custom client token
            return "jwt";

        } catch (Exception e) {
            // Unable to parse token structure
            logger.debug("Unable to parse JWT token structure: {}", e.getMessage());
            return "unknown";
        }
    }
}
