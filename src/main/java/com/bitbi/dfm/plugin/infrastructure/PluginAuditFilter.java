package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servlet filter for auditing plugin API requests.
 *
 * <p>Implements FR-014 requirements:</p>
 * <ul>
 *   <li>Captures request body hash (SHA-256, base64 encoded) - NOT plaintext</li>
 *   <li>Records request metadata (IP, user agent, duration)</li>
 *   <li>Logs after request completion to capture response status</li>
 * </ul>
 *
 * <p>This filter only processes requests to plugin endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/plugins/{pluginId}/activate</li>
 *   <li>DELETE /api/v1/plugins/{pluginId}/deactivate</li>
 * </ul>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PluginAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditFilter.class);

    // Pattern to match /api/v1/plugins/{pluginId}/activate or /api/v1/plugins/{pluginId}/deactivate
    private static final Pattern PLUGIN_API_PATTERN = Pattern.compile(
            "^/api/v1/plugins/([a-z0-9-]+)/(activate|deactivate)$"
    );

    /**
     * Maximum request body size to read for hashing (1MB).
     * Prevents memory exhaustion from malicious large payloads.
     */
    private static final int MAX_REQUEST_BODY_SIZE = 1024 * 1024; // 1MB

    /**
     * Maximum plugin ID length (matches DB column size).
     * Prevents issues from excessively long plugin IDs.
     */
    private static final int MAX_PLUGIN_ID_LENGTH = 64;

    private final PluginAuditLogRepository auditLogRepository;
    private final Auth0Properties auth0Properties;

    public PluginAuditFilter(PluginAuditLogRepository auditLogRepository, Auth0Properties auth0Properties) {
        this.auditLogRepository = auditLogRepository;
        this.auth0Properties = auth0Properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        Matcher matcher = PLUGIN_API_PATTERN.matcher(path);

        // Only process plugin API endpoints
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String pluginId = matcher.group(1);
        String action = matcher.group(2);
        String method = request.getMethod();

        // Validate plugin ID length (prevents issues with excessively long IDs)
        if (pluginId.length() > MAX_PLUGIN_ID_LENGTH) {
            log.warn("Plugin ID exceeds maximum length: {} (max: {})", pluginId.length(), MAX_PLUGIN_ID_LENGTH);
            filterChain.doFilter(request, response);
            return;
        }

        // Validate HTTP method matches action
        if (!isValidMethodForAction(method, action)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Wrap request and response to capture body and status
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long duration = System.currentTimeMillis() - startTime;

            // Log audit entry after request completion
            logAuditEntry(wrappedRequest, wrappedResponse, pluginId, action, duration);

            // Copy response body to original response
            wrappedResponse.copyBodyToResponse();
        }
    }

    /**
     * Creates and saves an audit log entry for the request.
     */
    private void logAuditEntry(
            ContentCachingRequestWrapper request,
            ContentCachingResponseWrapper response,
            String pluginId,
            String action,
            long durationMs) {

        try {
            PluginActionType actionType = mapActionToType(action, response.getStatus());
            UUID accountId = extractAccountId();
            String clientId = extractClientId();

            boolean success = isSuccessStatus(response.getStatus());

            PluginAuditLog auditLog;
            if (success) {
                auditLog = PluginAuditLog.success(pluginId, accountId, actionType);
            } else {
                auditLog = PluginAuditLog.failure(pluginId, accountId, actionType,
                        "HTTP " + response.getStatus());
            }

            // Add request body hash (SHA-256) - NEVER store plaintext (FR-014)
            byte[] requestBody = request.getContentAsByteArray();
            if (requestBody != null && requestBody.length > 0) {
                // Check size limit to prevent memory issues
                if (requestBody.length > MAX_REQUEST_BODY_SIZE) {
                    log.warn("Request body exceeds maximum size for hashing: {} bytes (max: {})",
                            requestBody.length, MAX_REQUEST_BODY_SIZE);
                    auditLog.withRequestBodyHash("BODY_TOO_LARGE", requestBody.length);
                } else {
                    String hash = computeSha256Hash(requestBody);
                    auditLog.withRequestBodyHash(hash, requestBody.length);
                }
            }

            // Add metadata
            auditLog.withClientId(clientId)
                    .withResponseStatus(response.getStatus())
                    .withIpAddress(getClientIp(request))
                    .withUserAgent(request.getHeader("User-Agent"))
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);

            log.debug("Audit logged via filter: {} plugin={} account={} status={} duration={}ms",
                    actionType, pluginId, accountId, response.getStatus(), durationMs);

        } catch (Exception e) {
            // Never fail the request due to audit logging errors
            log.error("Failed to create audit log entry: {}", e.getMessage());
        }
    }

    /**
     * Maps the action string and HTTP status to a PluginActionType.
     */
    private PluginActionType mapActionToType(String action, int status) {
        if ("activate".equals(action)) {
            // 201 = new activation, 200 = reactivation/update
            return status == 201 ? PluginActionType.ACTIVATE : PluginActionType.REACTIVATE;
        } else if ("deactivate".equals(action)) {
            return PluginActionType.DEACTIVATE;
        }
        return PluginActionType.ACTIVATE; // Fallback
    }

    /**
     * Determines if the HTTP status indicates success.
     */
    private boolean isSuccessStatus(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * Validates that the HTTP method matches the expected action.
     */
    private boolean isValidMethodForAction(String method, String action) {
        return ("activate".equals(action) && "POST".equalsIgnoreCase(method))
                || ("deactivate".equals(action) && "DELETE".equalsIgnoreCase(method));
    }

    /**
     * Extracts the account ID from the security context (Auth0 JWT claim).
     * Uses the configured claim namespace from Auth0Properties.
     */
    private UUID extractAccountId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Try namespaced claim first (e.g., https://test.dfm.bitbi.io/accountId)
                String accountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());
                if (accountIdStr != null && !accountIdStr.isBlank()) {
                    return UUID.fromString(accountIdStr);
                }
                // Fallback to legacy "accountId" claim
                accountIdStr = jwt.getClaimAsString("accountId");
                if (accountIdStr != null && !accountIdStr.isBlank()) {
                    return UUID.fromString(accountIdStr);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract accountId from JWT: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the client ID (azp claim) from the security context.
     */
    private String extractClientId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Try azp (authorized party) first, then client_id
                String azp = jwt.getClaimAsString("azp");
                if (azp != null) return azp;
                return jwt.getClaimAsString("client_id");
            }
        } catch (Exception e) {
            log.debug("Could not extract clientId from JWT: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Gets the client IP address, considering proxy headers.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the first IP in the chain (original client)
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Computes SHA-256 hash of data and returns base64-encoded result.
     *
     * @param data the data to hash
     * @return base64-encoded SHA-256 hash
     */
    private String computeSha256Hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only filter plugin API endpoints
        String path = request.getRequestURI();
        return !PLUGIN_API_PATTERN.matcher(path).matches();
    }
}
