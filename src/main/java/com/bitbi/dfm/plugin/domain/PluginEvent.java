package com.bitbi.dfm.plugin.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Value object representing an event dispatched to plugins.
 * Immutable record containing event metadata for plugin processing.
 */
public record PluginEvent(
    UUID eventId,
    PluginEventType type,
    UUID accountId,
    UUID resourceId,      // batchId, fileId, etc.
    Map<String, Object> metadata,
    Instant occurredAt
) {
    /**
     * Creates a PluginEvent with auto-generated eventId and current timestamp.
     */
    public static PluginEvent create(
            PluginEventType type,
            UUID accountId,
            UUID resourceId,
            Map<String, Object> metadata) {
        return new PluginEvent(
            UUID.randomUUID(),
            type,
            accountId,
            resourceId,
            metadata != null ? Map.copyOf(metadata) : Map.of(),
            Instant.now()
        );
    }

    /**
     * Creates a BATCH_COMPLETED event with batch metadata.
     */
    public static PluginEvent batchCompleted(
            UUID accountId,
            UUID batchId,
            int uploadedFilesCount,
            long totalSize) {
        return create(
            PluginEventType.BATCH_COMPLETED,
            accountId,
            batchId,
            Map.of(
                "uploadedFilesCount", uploadedFilesCount,
                "totalSize", totalSize
            )
        );
    }

    /**
     * Creates a BATCH_FAILED event with error details.
     */
    public static PluginEvent batchFailed(
            UUID accountId,
            UUID batchId,
            String errorMessage) {
        return create(
            PluginEventType.BATCH_FAILED,
            accountId,
            batchId,
            Map.of("errorMessage", errorMessage)
        );
    }

    /**
     * Creates a BATCH_EXPIRED event.
     */
    public static PluginEvent batchExpired(UUID accountId, UUID batchId) {
        return create(
            PluginEventType.BATCH_EXPIRED,
            accountId,
            batchId,
            Map.of()
        );
    }
}
