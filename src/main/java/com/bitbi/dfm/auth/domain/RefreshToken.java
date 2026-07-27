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
     * Identifies the rotation chain this token belongs to.
     * <p>
     * A chain starts at primary issuance (one Device Authorization Flow run) and every rotation
     * inherits it. Its purpose is to scope <b>reuse detection</b>: replaying a consumed token
     * revokes that chain rather than every token the site ever had, so tokens left over from
     * earlier sessions cannot be replayed to kill the current one.
     * </p>
     * <p>
     * It does <b>not</b> mean a site runs several sessions in parallel. One site is one client
     * installation: re-running the device flow with the same site name reattaches to the same
     * site and revokes its tokens site-wide, so at most one family is live at a time.
     * </p>
     * <p>
     * Never null: {@code NOT NULL} with a database-side default since V43.
     * </p>
     */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

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

    private RefreshToken(UUID id, UUID siteId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.siteId = siteId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /**
     * Create a refresh token that starts a new rotation family, with default 90-day TTL.
     * <p>
     * This is primary issuance - one authenticated session, e.g. a completed Device
     * Authorization Flow. Existing families of the same site are left untouched.
     * </p>
     *
     * @param siteId    site identifier
     * @param tokenHash SHA-256 hash of the opaque token
     * @return new RefreshToken entity heading a fresh family
     */
    public static RefreshToken createNewFamily(UUID siteId, String tokenHash) {
        return newToken(siteId, UUID.randomUUID(), tokenHash);
    }

    /**
     * Create the successor of {@code parent} within the same rotation family.
     *
     * @param parent    the token being rotated out
     * @param tokenHash SHA-256 hash of the new opaque token
     * @return new RefreshToken entity inheriting the parent's site and family
     */
    public static RefreshToken rotateFrom(RefreshToken parent, String tokenHash) {
        return newToken(parent.siteId, parent.familyId, tokenHash);
    }

    private static RefreshToken newToken(UUID siteId, UUID familyId, String tokenHash) {
        return new RefreshToken(
                UUID.randomUUID(),
                siteId,
                familyId,
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
