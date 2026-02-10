package com.bitbi.dfm.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a refresh token for device authentication.
 * <p>
 * Refresh tokens are opaque strings stored as SHA-256 hashes.
 * They support rotation: on each use, the old token is revoked
 * and a new one is issued.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshToken {

    private static final long DEFAULT_TTL_DAYS = 90;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    /**
     * SHA-256 hash of the opaque refresh token string.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the token was revoked. NULL means the token is active.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    private RefreshToken(UUID id, UUID siteId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.siteId = siteId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /**
     * Create a new refresh token with default 90-day TTL.
     *
     * @param siteId    site identifier
     * @param tokenHash SHA-256 hash of the opaque token
     * @return new RefreshToken entity
     */
    public static RefreshToken create(UUID siteId, String tokenHash) {
        return new RefreshToken(
                UUID.randomUUID(),
                siteId,
                tokenHash,
                Instant.now().plusSeconds(DEFAULT_TTL_DAYS * 24 * 60 * 60),
                Instant.now()
        );
    }

    /**
     * Check if the token is expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if the token has been revoked.
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Check if the token is still valid (not expired and not revoked).
     */
    public boolean isValid() {
        return !isExpired() && !isRevoked();
    }

    /**
     * Revoke this refresh token.
     */
    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }
}
