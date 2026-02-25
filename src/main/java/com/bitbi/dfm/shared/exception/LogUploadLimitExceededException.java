package com.bitbi.dfm.shared.exception;

import java.util.UUID;

/**
 * Exception thrown when a site exceeds the daily log upload limit.
 * <p>
 * Each site is limited to a configurable number of log uploads per day (default: 10).
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US4 - Client Uploads Diagnostic Logs
 */
public class LogUploadLimitExceededException extends RuntimeException {

    public LogUploadLimitExceededException(UUID siteId, int dailyLimit) {
        super(String.format("Site %s has exceeded the daily log upload limit of %d", siteId, dailyLimit));
    }

    public LogUploadLimitExceededException(String message) {
        super(message);
    }
}
