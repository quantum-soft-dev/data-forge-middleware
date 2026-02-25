package com.bitbi.dfm.shared.exception;

/**
 * Exception thrown when a client uploads an invalid log file.
 * <p>
 * Invalid log files include unsupported file types (only .log, .log.gz, .txt, .txt.gz allowed)
 * and empty files.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US4 - Client Uploads Diagnostic Logs
 */
public class InvalidLogFileException extends RuntimeException {

    public InvalidLogFileException(String message) {
        super(message);
    }

    public InvalidLogFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
