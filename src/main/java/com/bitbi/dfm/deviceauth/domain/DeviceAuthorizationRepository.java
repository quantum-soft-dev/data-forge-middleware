package com.bitbi.dfm.deviceauth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for device authorization operations.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface DeviceAuthorizationRepository {

    /**
     * Save a device authorization.
     *
     * @param authorization the authorization to save
     * @return saved authorization
     */
    DeviceAuthorization save(DeviceAuthorization authorization);

    /**
     * Find authorization by device code.
     *
     * @param deviceCode the device code
     * @return authorization if found
     */
    Optional<DeviceAuthorization> findByDeviceCode(String deviceCode);

    /**
     * Find authorization by user code.
     *
     * @param userCode the user code
     * @return authorization if found
     */
    Optional<DeviceAuthorization> findByUserCode(String userCode);

    /**
     * Check if user code already exists.
     *
     * @param userCode the user code to check
     * @return true if exists
     */
    boolean existsByUserCode(String userCode);

    /**
     * Delete expired pending authorizations.
     *
     * @param cutoffTime authorizations with expiresAt before this time will be deleted
     * @return number of deleted records
     */
    int deleteExpiredPending(Instant cutoffTime);

    /**
     * Mark expired pending authorizations as EXPIRED.
     *
     * @param cutoffTime authorizations with expiresAt before this time
     * @return number of updated records
     */
    int markExpired(Instant cutoffTime);

    /**
     * Delete all device authorizations for a site.
     *
     * @param siteId site identifier
     */
    void deleteBySiteId(UUID siteId);
}
