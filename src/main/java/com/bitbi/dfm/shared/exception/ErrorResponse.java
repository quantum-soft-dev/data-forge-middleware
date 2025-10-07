package com.bitbi.dfm.shared.exception;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Standardized error response DTO.
 * <p>
 * Used by GlobalExceptionHandler for consistent error formatting across all endpoints.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record ErrorResponse(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path);
    }
}
