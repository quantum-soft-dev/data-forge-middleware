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
     * Detect token type from request headers using structural JWT analysis.
     *
     * Parses the JWT header to determine token type based on:
     * - Algorithm (alg): "HS256" = custom JWT, "RS256" = Keycloak
     * - Issuer (iss): Presence of Keycloak issuer URL
     * - Type (typ): JWT type identifier
     *
     * Returns "jwt", "keycloak", or "unknown".
     */
    private String detectTokenType(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        String keycloakTokenHeader = request.getHeader("X-Keycloak-Token");

        // Check X-Keycloak-Token header first (explicit Keycloak indicator)
        if (keycloakTokenHeader != null) {
            return "keycloak";
        }

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
     * Decodes the JWT header and inspects:
     * - Algorithm (alg): HS256/HS384/HS512 = custom JWT, RS256/RS384/RS512 = Keycloak
     * - Token type (typ): Should be "JWT"
     *
     * @param token JWT token string
     * @return "jwt" for custom tokens, "keycloak" for Keycloak tokens, "unknown" if unable to parse
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
                // RSA algorithms (RS256, RS384, RS512) = Keycloak
                if (algorithm.startsWith("RS")) {
                    return "keycloak";
                }
            }

            // Fallback: check payload for Keycloak-specific claims
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode payload = mapper.readTree(payloadJson);

            // Keycloak tokens have "iss" (issuer) field pointing to Keycloak server
            if (payload.has("iss")) {
                String issuer = payload.get("iss").asText();
                if (issuer.contains("keycloak") || issuer.contains("/realms/")) {
                    return "keycloak";
                }
            }

            // If we got here, it's likely a custom JWT with unknown algorithm
            return "jwt";

        } catch (Exception e) {
            // Unable to parse token structure
            logger.debug("Unable to parse JWT token structure: {}", e.getMessage());
            return "unknown";
        }
    }
}
