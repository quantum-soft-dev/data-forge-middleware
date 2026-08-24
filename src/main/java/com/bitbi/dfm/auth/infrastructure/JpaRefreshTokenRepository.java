package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Native, and only because JPQL has no AT TIME ZONE (issue #286): revoked_at is a
    // zone-independent TIMESTAMP holding a UTC wall clock, while a bare CURRENT_TIMESTAMP is
    // resolved in the database session's zone, which pgjdbc takes from the JVM's default. The
    // entity's own writer binds an Instant, which is always UTC, so off UTC one column would have
    // received two different clocks. Same expression as the catalog watermark in
    // JpaBatchParquetArtifactRepository; the database stays the time source (#245).
    @Override
    @Modifying
    @Query(value = "UPDATE refresh_tokens SET revoked_at = CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) "
            + "WHERE site_id = :siteId AND revoked_at IS NULL", nativeQuery = true)
    int revokeAllBySiteId(UUID siteId);

    @Override
    @Modifying
    @Query(value = "UPDATE refresh_tokens SET revoked_at = CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) "
            + "WHERE family_id = :familyId AND revoked_at IS NULL", nativeQuery = true)
    int revokeAllByFamilyId(UUID familyId);

    @Override
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoffTime OR (rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoffTime)")
    int deleteExpiredBefore(Instant cutoffTime);
}
