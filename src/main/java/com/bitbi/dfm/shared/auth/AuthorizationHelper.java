package com.bitbi.dfm.shared.auth;

import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper class for authorization checks.
 * <p>
 * Provides utility methods to verify that authenticated users can access
 * only their own resources.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class AuthorizationHelper {

    /**
     * Get the authenticated site ID from security context.
     *
     * @return site ID from JWT token
     * @throws UnauthorizedException if not authenticated or not a JWT token
     */
    public UUID getAuthenticatedSiteId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }

        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new UnauthorizedException("Invalid authentication type");
        }

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return jwtAuth.getSiteId();
    }

    /**
     * Get the authenticated account ID from security context.
     *
     * @return account ID from JWT token
     * @throws UnauthorizedException if not authenticated or not a JWT token
     */
    public UUID getAuthenticatedAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }

        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new UnauthorizedException("Invalid authentication type");
        }

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return jwtAuth.getAccountId();
    }

    /**
     * Get the authenticated domain from security context.
     *
     * @return domain from JWT token
     * @throws UnauthorizedException if not authenticated or not a JWT token
     */
    public String getAuthenticatedDomain() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }

        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new UnauthorizedException("Invalid authentication type");
        }

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return jwtAuth.getDomain();
    }

    /**
     * Verify that the authenticated site matches the expected site ID.
     *
     * @param expectedSiteId expected site ID
     * @throws UnauthorizedException if site IDs don't match
     */
    public void verifySiteOwnership(UUID expectedSiteId) {
        UUID authenticatedSiteId = getAuthenticatedSiteId();

        if (!authenticatedSiteId.equals(expectedSiteId)) {
            throw new UnauthorizedException("Access denied: site ownership mismatch");
        }
    }

    /**
     * Verify that the authenticated account matches the expected account ID.
     *
     * @param expectedAccountId expected account ID
     * @throws UnauthorizedException if account IDs don't match
     */
    public void verifyAccountOwnership(UUID expectedAccountId) {
        UUID authenticatedAccountId = getAuthenticatedAccountId();

        if (!authenticatedAccountId.equals(expectedAccountId)) {
            throw new UnauthorizedException("Access denied: account ownership mismatch");
        }
    }

    /**
     * Exception thrown when authorization fails.
     */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
