package com.bitbi.dfm.shared.auth;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper class for authorization checks.
 * <p>
 * Provides utility methods to verify that authenticated users can access
 * only their own resources. Supports both custom JWT tokens (/api/dfc endpoints)
 * and Keycloak OAuth2 JWT tokens (/api/user, /api/admin endpoints).
 * </p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 */
@Component
public class AuthorizationHelper {

    private final AccountRepository accountRepository;

    public AuthorizationHelper(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Get the authenticated site ID from security context.
     * <p>
     * For custom JWT tokens: extracts from JwtAuthenticationToken.getSiteId()
     * For OAuth2 JWT tokens: throws exception (not applicable for OAuth2 endpoints)
     * </p>
     *
     * @return site ID from JWT token
     * @throws UnauthorizedException if not authenticated or not a custom JWT token
     */
    public UUID getAuthenticatedSiteId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }

        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new UnauthorizedException("Invalid authentication type for client API");
        }

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return jwtAuth.getSiteId();
    }

    /**
     * Get the authenticated account ID from security context.
     * <p>
     * Supports two authentication types:
     * - Custom JWT tokens (JwtAuthenticationToken): extracts accountId from token claims
     * - OAuth2 JWT tokens (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken): extracts from "subject" or "accountId" claim
     * </p>
     *
     * @return account ID from JWT token
     * @throws UnauthorizedException if not authenticated or accountId claim not found
     */
    public UUID getAuthenticatedAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }

        // Custom JWT token (for /api/dfc endpoints)
        if (authentication instanceof JwtAuthenticationToken) {
            JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
            return jwtAuth.getAccountId();
        }

        // OAuth2 JWT token (for /api/user, /api/admin endpoints)
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken oauth2JwtAuth =
                    (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) authentication;
            Jwt jwt = oauth2JwtAuth.getToken();

            // Strategy 1: Try to extract accountId from Auth0 custom claim (namespaced)
            String accountIdClaim = jwt.getClaimAsString("https://api.dataforge.com/accountId");
            if (accountIdClaim != null && !accountIdClaim.isEmpty()) {
                try {
                    return UUID.fromString(accountIdClaim);
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID, try next strategy
                }
            }

            // Strategy 2: Try to extract accountId from direct "accountId" claim (legacy Keycloak)
            accountIdClaim = jwt.getClaimAsString("accountId");
            if (accountIdClaim != null && !accountIdClaim.isEmpty()) {
                try {
                    return UUID.fromString(accountIdClaim);
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID, try next strategy
                }
            }

            // Strategy 3: Try to extract from Keycloak user attributes (custom mapper)
            // Keycloak can be configured to include user attributes in JWT via mappers
            Object accountIdAttr = jwt.getClaim("account_id");
            if (accountIdAttr != null) {
                try {
                    return UUID.fromString(accountIdAttr.toString());
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID, try next strategy
                }
            }

            // Strategy 3: Extract from subject claim (if it's a UUID)
            // This works when Keycloak subject is the account ID
            String subject = jwt.getSubject();
            if (subject != null && !subject.isEmpty()) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException e) {
                    // Subject is not a UUID (probably Keycloak user ID)
                    // Continue to Strategy 4 (database lookup)
                }
            }

            // Strategy 4: Look up account by email in database
            // This is the fallback strategy when accountId is not in JWT claims
            String email = jwt.getClaimAsString("email");
            if (email == null || email.isEmpty()) {
                email = jwt.getClaimAsString("preferred_username");
            }

            if (email != null && !email.isEmpty()) {
                Account account = accountRepository.findByEmail(email).orElse(null);
                if (account != null) {
                    return account.getId();
                } else {
                    throw new UnauthorizedException(
                        "Account not found for email: " + email + ". " +
                        "User must have a registered account to access this resource."
                    );
                }
            }

            // If we reach here, accountId is not configured in Keycloak JWT and no email found
            // Log available claims for debugging
            throw new UnauthorizedException(
                "Account ID not found in JWT token. Please configure Keycloak user mapper to include 'accountId' attribute " +
                "or ensure 'email' claim is present for database lookup. " +
                "Available claims: " + jwt.getClaims().keySet()
            );
        }

        throw new UnauthorizedException("Invalid authentication type");
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
