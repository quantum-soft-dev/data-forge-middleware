package com.bitbi.dfm.plugin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for plugin audit log entries.
 * Records all plugin API calls and event executions for monitoring and compliance.
 *
 * <p>The table is partitioned by month (occurred_at) for efficient querying.
 * Retention is indefinite per specification.</p>
 *
 * <p>Per FR-014, request bodies are NEVER stored in plaintext - only SHA-256 hashes.</p>
 */
@Entity
@Table(name = "plugin_audit_logs")
@Getter
@NoArgsConstructor
public class PluginAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", length = 64, nullable = false)
    private String pluginId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "client_id", length = 64)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 50, nullable = false)
    private PluginActionType actionType;

    @Column(name = "request_body_hash", length = 64)
    private String requestBodyHash;

    @Column(name = "request_body_size")
    private Integer requestBodySize;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * Creates a successful audit log entry.
     */
    public static PluginAuditLog success(
            String pluginId,
            UUID accountId,
            PluginActionType actionType) {
        PluginAuditLog log = new PluginAuditLog();
        log.pluginId = pluginId;
        log.accountId = accountId;
        log.actionType = actionType;
        log.success = true;
        log.occurredAt = Instant.now();
        return log;
    }

    /**
     * Creates a failed audit log entry.
     */
    public static PluginAuditLog failure(
            String pluginId,
            UUID accountId,
            PluginActionType actionType,
            String errorMessage) {
        PluginAuditLog log = new PluginAuditLog();
        log.pluginId = pluginId;
        log.accountId = accountId;
        log.actionType = actionType;
        log.success = false;
        log.errorMessage = truncateErrorMessage(errorMessage);
        log.occurredAt = Instant.now();
        return log;
    }

    /**
     * Builder-style method to set client ID.
     */
    public PluginAuditLog withClientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    /**
     * Builder-style method to set request body hash (SHA-256, base64).
     */
    public PluginAuditLog withRequestBodyHash(String hash, int size) {
        this.requestBodyHash = hash;
        this.requestBodySize = size;
        return this;
    }

    /**
     * Builder-style method to set HTTP response status.
     */
    public PluginAuditLog withResponseStatus(int status) {
        this.responseStatus = status;
        return this;
    }

    /**
     * Builder-style method to set client IP address.
     */
    public PluginAuditLog withIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
        return this;
    }

    /**
     * Builder-style method to set user agent.
     */
    public PluginAuditLog withUserAgent(String userAgent) {
        this.userAgent = truncateUserAgent(userAgent);
        return this;
    }

    /**
     * Builder-style method to set operation duration.
     */
    public PluginAuditLog withDuration(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    /**
     * Sets the occurred timestamp (default is Instant.now()).
     */
    public PluginAuditLog withOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
        return this;
    }

    private static String truncateErrorMessage(String message) {
        if (message == null) return null;
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static String truncateUserAgent(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
