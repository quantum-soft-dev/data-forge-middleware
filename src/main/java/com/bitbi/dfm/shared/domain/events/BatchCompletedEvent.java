package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Domain event fired when a batch is completed.
 *
 * <p>Extended to include accountId for plugin event dispatch (Phase 7 - US5).
 * The accountId is nullable for backward compatibility with existing code.</p>
 */
public record BatchCompletedEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID batchId,
        UUID accountId,
        int uploadedFilesCount,
        long totalSize
) implements DomainEvent {

    /**
     * Creates a BatchCompletedEvent with accountId (preferred constructor for plugin dispatch).
     *
     * @param batchId the batch identifier
     * @param accountId the account identifier (required for plugin event dispatch)
     * @param uploadedFilesCount number of files uploaded in the batch
     * @param totalSize total size in bytes
     */
    public BatchCompletedEvent(UUID batchId, UUID accountId, int uploadedFilesCount, long totalSize) {
        this(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC), batchId, accountId, uploadedFilesCount, totalSize);
    }

    /**
     * Legacy constructor without accountId (for backward compatibility).
     *
     * @param batchId the batch identifier
     * @param uploadedFilesCount number of files uploaded in the batch
     * @param totalSize total size in bytes
     * @deprecated Use constructor with accountId for plugin event support
     */
    @Deprecated
    public BatchCompletedEvent(UUID batchId, int uploadedFilesCount, long totalSize) {
        this(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC), batchId, null, uploadedFilesCount, totalSize);
    }

    @Override
    public String getEventType() {
        return "BatchCompleted";
    }
}
