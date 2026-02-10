package com.bitbi.dfm.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for refresh token operations.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface RefreshTokenRepository {

    /**
     * Save a refresh token.
     *
     * @param refreshToken the token to save
     * @return saved token
     */
    RefreshToken save(RefreshToken refreshToken);

    /**
     * Find a refresh token by its SHA-256 hash.
     *
     * @param tokenHash SHA-256 hash of the opaque token
     * @return token if found
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoke all active refresh tokens for a site.
     *
     * @param siteId site identifier
     * @return number of revoked tokens
     */
    int revokeAllBySiteId(UUID siteId);

    /**
     * Delete expired and revoked tokens older than cutoff.
     *
     * @param cutoffTime tokens with expiresAt before this time will be deleted
     * @return number of deleted records
     */
    int deleteExpiredBefore(Instant cutoffTime);
}
