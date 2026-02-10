package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of RefreshTokenRepository.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaRefreshTokenRepository extends JpaRepository<RefreshToken, UUID>, RefreshTokenRepository {

    @Override
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Override
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = CURRENT_TIMESTAMP WHERE rt.siteId = :siteId AND rt.revokedAt IS NULL")
    int revokeAllBySiteId(UUID siteId);

    @Override
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoffTime AND (rt.revokedAt IS NOT NULL OR rt.expiresAt < CURRENT_TIMESTAMP)")
    int deleteExpiredBefore(Instant cutoffTime);
}
