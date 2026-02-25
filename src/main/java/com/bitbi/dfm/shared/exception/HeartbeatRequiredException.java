package com.bitbi.dfm.shared.exception;

import java.util.UUID;

/**
 * Exception thrown when a batch start is attempted without a recent heartbeat.
 * <p>
 * Clients must call GET /api/v1/device/heartbeat before starting a batch.
 * The heartbeat must be within the configured required interval.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US2 - Heartbeat Pre-Batch Sync Check
 */
public class HeartbeatRequiredException extends RuntimeException {

    public HeartbeatRequiredException(UUID siteId) {
        super(String.format("Heartbeat required before starting a batch for site %s. Call GET /api/v1/device/heartbeat first.", siteId));
    }

    public HeartbeatRequiredException(String message) {
        super(message);
    }
}
