package com.bitbi.dfm.site.domain;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing site authentication credentials.
 * <p>
 * Encapsulates domain and clientSecret pair used for Basic Authentication.
 * Immutable and validated at construction time.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record SiteCredentials(String domain, String clientSecret) {

    /**
     * Constructs SiteCredentials with validation.
     *
     * @param domain       unique domain name (e.g., "store-01.example.com")
     * @param clientSecret UUID-based secret for authentication
     * @throws IllegalArgumentException if domain or clientSecret is null/empty
     */
    public SiteCredentials {
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(clientSecret, "Client secret cannot be null");

        if (domain.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be blank");
        }

        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException("Client secret cannot be blank");
        }

        // Validate domain format (basic check)
        if (!domain.contains(".")) {
            throw new IllegalArgumentException("Domain must be a valid domain name");
        }
    }

    /**
     * Generate new credentials with random UUID-based clientSecret.
     *
     * @param domain the site domain
     * @return new SiteCredentials with generated secret
     */
    public static SiteCredentials generate(String domain) {
        String clientSecret = UUID.randomUUID().toString();
        return new SiteCredentials(domain, clientSecret);
    }

    /**
     * Validate credentials against provided domain and secret.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param providedDomain       domain to validate
     * @param providedClientSecret secret to validate
     * @return true if credentials match
     */
    public boolean matches(String providedDomain, String providedClientSecret) {
        if (providedDomain == null || providedClientSecret == null) {
            return false;
        }

        // Constant-time comparison for security
        boolean domainMatches = MessageDigest.isEqual(
                domain.getBytes(),
                providedDomain.getBytes()
        );

        boolean secretMatches = MessageDigest.isEqual(
                clientSecret.getBytes(),
                providedClientSecret.getBytes()
        );

        return domainMatches && secretMatches;
    }
}
