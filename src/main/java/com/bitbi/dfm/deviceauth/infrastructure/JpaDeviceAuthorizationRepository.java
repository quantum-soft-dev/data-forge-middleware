package com.bitbi.dfm.deviceauth.infrastructure;

import com.bitbi.dfm.deviceauth.domain.DeviceAuthorization;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationRepository;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of DeviceAuthorizationRepository.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaDeviceAuthorizationRepository
        extends JpaRepository<DeviceAuthorization, UUID>, DeviceAuthorizationRepository {

    @Override
    Optional<DeviceAuthorization> findByDeviceCode(String deviceCode);

    @Override
    Optional<DeviceAuthorization> findByUserCode(String userCode);

    @Override
    boolean existsByUserCode(String userCode);

    @Override
    @Modifying
    @Query("DELETE FROM DeviceAuthorization da WHERE da.status = 'PENDING' AND da.expiresAt < :cutoffTime")
    int deleteExpiredPending(@Param("cutoffTime") Instant cutoffTime);

    @Modifying
    @Query("UPDATE DeviceAuthorization da SET da.status = :expiredStatus " +
           "WHERE da.status = :pendingStatus AND da.expiresAt < :cutoffTime")
    int markExpiredInternal(@Param("cutoffTime") Instant cutoffTime,
                           @Param("pendingStatus") DeviceAuthorizationStatus pendingStatus,
                           @Param("expiredStatus") DeviceAuthorizationStatus expiredStatus);

    /**
     * Mark expired pending authorizations as EXPIRED.
     */
    @Override
    default int markExpired(Instant cutoffTime) {
        return markExpiredInternal(cutoffTime,
                DeviceAuthorizationStatus.PENDING,
                DeviceAuthorizationStatus.EXPIRED);
    }

    @Override
    @Modifying
    @Query("DELETE FROM DeviceAuthorization d WHERE d.siteId = :siteId")
    void deleteBySiteId(@Param("siteId") UUID siteId);
}
