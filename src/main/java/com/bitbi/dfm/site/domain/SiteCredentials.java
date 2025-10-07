package com.bitbi.dfm.site.domain;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing site authentication credentials.
 * <p>
 * Encapsulates domain and clientSecretHash pair used for Basic Authentication.
 * Uses BCrypt for secure password hashing.
 * Immutable and validated at construction time.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record SiteCredentials(String domain, String clientSecretHash) {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * Constructs SiteCredentials with validation.
     *
     * @param domain             unique domain name (e.g., "store-01.example.com")
     * @param clientSecretHash   bcrypt-hashed secret for authentication
     * @throws IllegalArgumentException if domain or clientSecretHash is null/empty
     */
    public SiteCredentials {
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(clientSecretHash, "Client secret hash cannot be null");

        if (domain.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be blank");
        }

        if (clientSecretHash.isBlank()) {
            throw new IllegalArgumentException("Client secret hash cannot be blank");
        }

        // Validate domain format (basic check)
        if (!domain.contains(".")) {
            throw new IllegalArgumentException("Domain must be a valid domain name");
        }
    }

    /**
     * Generate new credentials with random UUID-based clientSecret and bcrypt hash.
     *
     * @param domain the site domain
     * @return array [0]=plaintext secret (return to user), [1]=hashed secret (store in DB)
     */
    public static String[] generateWithHash(String domain) {
        String plaintextSecret = UUID.randomUUID().toString();
        String hashedSecret = PASSWORD_ENCODER.encode(plaintextSecret);
        return new String[]{plaintextSecret, hashedSecret};
    }

    /**
     * Verify provided secret against stored bcrypt hash.
     * Uses BCrypt's built-in constant-time comparison to prevent timing attacks.
     *
     * @param providedSecret plaintext secret to verify
     * @return true if secret matches the hash
     */
    public boolean verifySecret(String providedSecret) {
        if (providedSecret == null) {
            return false;
        }

        try {
            return PASSWORD_ENCODER.matches(providedSecret, clientSecretHash);
        } catch (IllegalArgumentException e) {
            // Invalid hash format
            return false;
        }
    }
}
