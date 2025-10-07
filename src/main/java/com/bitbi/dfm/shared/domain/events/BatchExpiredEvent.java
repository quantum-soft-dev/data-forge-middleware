package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event fired when a batch expires due to timeout.
 */
public record BatchExpiredEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID batchId
) implements DomainEvent {

    public BatchExpiredEvent(UUID batchId) {
        this(UUID.randomUUID(), LocalDateTime.now(), batchId);
    }

    @Override
    public String getEventType() {
        return "BatchExpired";
    }
}
