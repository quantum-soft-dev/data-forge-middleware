package com.bitbi.dfm.error.domain;

/**
 * Error severity levels for global error handling.
 * <p>
 * Stored as VARCHAR in database using @Enumerated(EnumType.STRING).
 * Default severity is ERROR.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum ErrorSeverity {

    /**
     * Critical severity - System failure, data loss risk.
     */
    CRITICAL,

    /**
     * Error severity - Operation failed (default).
     */
    ERROR,

    /**
     * Warning severity - Degraded performance, recoverable.
     */
    WARNING,

    /**
     * Info severity - Informational, expected condition.
     */
    INFO;

    /**
     * Default severity level for errors.
     */
    public static final ErrorSeverity DEFAULT = ERROR;
}
