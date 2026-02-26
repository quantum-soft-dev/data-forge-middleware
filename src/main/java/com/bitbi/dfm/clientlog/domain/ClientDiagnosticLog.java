package com.bitbi.dfm.clientlog.domain;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Entity representing a client diagnostic log file uploaded for troubleshooting.
 * <p>
 * Clients upload log files (.log, .log.gz, .txt, .txt.gz) via the device API.
 * Files are stored in S3 with metadata in PostgreSQL. Logs auto-expire after
 * a configurable retention period (default: 30 days).
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US4 - Client Uploads Diagnostic Logs
 */
@Entity
@Table(name = "client_diagnostic_logs")
@Getter
@NoArgsConstructor
public class ClientDiagnosticLog {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "client_version", length = 100)
    private String clientVersion;

    @Column(name = "os", length = 200)
    private String os;

    @Column(name = "period_from")
    private LocalDateTime periodFrom;

    @Column(name = "period_to")
    private LocalDateTime periodTo;

    @Type(JsonType.class)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Create a new client diagnostic log entry.
     *
     * @param siteId        site identifier
     * @param accountId     account identifier
     * @param s3Key         S3 storage key
     * @param filename      original filename
     * @param fileSize      file size in bytes
     * @param contentType   MIME content type
     * @param clientVersion client application version
     * @param os            client operating system
     * @param periodFrom    log period start
     * @param periodTo      log period end
     * @param tags          tags for filtering
     * @param description   problem description
     * @param retentionDays number of days before auto-deletion
     * @return new ClientDiagnosticLog entity
     */
    public static ClientDiagnosticLog create(
            UUID siteId,
            UUID accountId,
            String s3Key,
            String filename,
            Long fileSize,
            String contentType,
            String clientVersion,
            String os,
            LocalDateTime periodFrom,
            LocalDateTime periodTo,
            List<String> tags,
            String description,
            int retentionDays
    ) {
        ClientDiagnosticLog log = new ClientDiagnosticLog();
        log.id = UUID.randomUUID();
        log.siteId = siteId;
        log.accountId = accountId;
        log.s3Key = s3Key;
        log.filename = filename;
        log.fileSize = fileSize;
        log.contentType = contentType;
        log.clientVersion = clientVersion;
        log.os = os;
        log.periodFrom = periodFrom;
        log.periodTo = periodTo;
        log.tags = tags;
        log.description = description;
        log.uploadedAt = LocalDateTime.now();
        log.expiresAt = LocalDateTime.now().plusDays(retentionDays);
        return log;
    }
}
