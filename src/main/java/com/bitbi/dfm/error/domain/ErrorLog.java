package com.bitbi.dfm.error.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ErrorLog entity with time-based partitioning.
 * <p>
 * Records errors from client applications with JSONB metadata support.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "error_logs")
@Getter
@NoArgsConstructor
public class ErrorLog {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "client_version", length = 50)
    private String clientVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ErrorSeverity severity = ErrorSeverity.ERROR;

    @Setter
    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    protected ErrorLog(UUID id, UUID siteId, UUID batchId, String type, String title,
                       String message, String stackTrace, String clientVersion,
                       Map<String, Object> metadata, LocalDateTime occurredAt, LocalDateTime createdAt,
                       ErrorSeverity severity, boolean isRead) {
        this.id = id;
        this.siteId = siteId;
        this.batchId = batchId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.stackTrace = stackTrace;
        this.clientVersion = clientVersion;
        this.metadata = metadata;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.severity = severity != null ? severity : ErrorSeverity.ERROR;
        this.isRead = isRead;
    }

    /**
     * Create a new ErrorLog with default severity (ERROR).
     * <p>
     * Backwards-compatible factory method for existing code.
     * </p>
     */
    public static ErrorLog create(UUID siteId, UUID batchId, String type, String title,
                                  String message, String stackTrace, String clientVersion,
                                  Map<String, Object> metadata) {
        return create(siteId, batchId, type, title, message, stackTrace, clientVersion, metadata, ErrorSeverity.ERROR);
    }

    /**
     * Create a new ErrorLog with specified severity.
     * <p>
     * New factory method for global error handling with severity support.
     * </p>
     *
     * @param siteId        site identifier
     * @param batchId       batch identifier (null for global errors)
     * @param type          error type/category
     * @param title         error title
     * @param message       error message (max 10,000 chars)
     * @param stackTrace    optional stack trace
     * @param clientVersion optional client version
     * @param metadata      optional JSONB metadata
     * @param severity      error severity level (defaults to ERROR if null)
     * @return new ErrorLog instance
     */
    public static ErrorLog create(UUID siteId, UUID batchId, String type, String title,
                                  String message, String stackTrace, String clientVersion,
                                  Map<String, Object> metadata, ErrorSeverity severity) {
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(title, "Title cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        // Sanitize strings to remove null bytes (0x00) which are invalid in PostgreSQL UTF-8
        String sanitizedType = sanitizeString(type);
        String sanitizedTitle = sanitizeString(title);
        String sanitizedMessage = sanitizeString(truncateMessage(message));
        String sanitizedStackTrace = sanitizeString(stackTrace);
        String sanitizedClientVersion = sanitizeString(clientVersion);

        ErrorSeverity effectiveSeverity = severity != null ? severity : ErrorSeverity.ERROR;

        return new ErrorLog(id, siteId, batchId, sanitizedType, sanitizedTitle, sanitizedMessage,
                sanitizedStackTrace, sanitizedClientVersion, metadata, now, now, effectiveSeverity, false);
    }

    /**
     * Truncate message to maximum allowed length (10,000 characters).
     *
     * @param message the message to truncate
     * @return truncated message or original if within limits
     */
    private static String truncateMessage(String message) {
        if (message == null) {
            return null;
        }
        int maxLength = 10000;
        return message.length() > maxLength ? message.substring(0, maxLength) : message;
    }

    /**
     * Sanitize string by removing null bytes (0x00) which are invalid in PostgreSQL UTF-8.
     *
     * @param input the input string to sanitize
     * @return sanitized string without null bytes, or null if input is null
     */
    private static String sanitizeString(String input) {
        if (input == null) {
            return null;
        }
        // Remove all null bytes (0x00) from the string
        return input.replace("\u0000", "");
    }

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) occurredAt = LocalDateTime.now();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ErrorLog errorLog)) return false;
        return Objects.equals(id, errorLog.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
