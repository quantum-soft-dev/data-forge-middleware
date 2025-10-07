package com.bitbi.dfm.error.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

    protected ErrorLog(UUID id, UUID siteId, UUID batchId, String type, String title,
                       String message, String stackTrace, String clientVersion,
                       Map<String, Object> metadata, LocalDateTime occurredAt, LocalDateTime createdAt) {
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
    }

    public static ErrorLog create(UUID siteId, UUID batchId, String type, String title,
                                  String message, String stackTrace, String clientVersion,
                                  Map<String, Object> metadata) {
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(title, "Title cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        return new ErrorLog(id, siteId, batchId, type, title, message, stackTrace,
                clientVersion, metadata, now, now);
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
