package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event fired when a batch is completed.
 */
public record BatchCompletedEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID batchId,
        int uploadedFilesCount,
        long totalSize
) implements DomainEvent {

    public BatchCompletedEvent(UUID batchId, int uploadedFilesCount, long totalSize) {
        this(UUID.randomUUID(), LocalDateTime.now(), batchId, uploadedFilesCount, totalSize);
    }

    @Override
    public String getEventType() {
        return "BatchCompleted";
    }
}
