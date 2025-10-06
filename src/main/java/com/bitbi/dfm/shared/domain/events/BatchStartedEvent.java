package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event fired when a batch is started.
 * <p>
 * Used for metrics collection and logging.
 * </p>
 */
public record BatchStartedEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID batchId,
        UUID siteId,
        UUID accountId
) implements DomainEvent {

    public BatchStartedEvent(UUID batchId, UUID siteId, UUID accountId) {
        this(UUID.randomUUID(), LocalDateTime.now(), batchId, siteId, accountId);
    }

    @Override
    public String getEventType() {
        return "BatchStarted";
    }
}
