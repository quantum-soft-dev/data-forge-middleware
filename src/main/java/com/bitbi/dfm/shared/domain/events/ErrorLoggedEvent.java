package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event fired when an error is logged.
 * <p>
 * Triggers update of batch hasErrors flag and metrics collection.
 * </p>
 */
public record ErrorLoggedEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID errorLogId,
        UUID siteId,
        UUID batchId,
        String type
) implements DomainEvent {

    public ErrorLoggedEvent(UUID errorLogId, UUID siteId, UUID batchId, String type) {
        this(UUID.randomUUID(), LocalDateTime.now(), errorLogId, siteId, batchId, type);
    }

    @Override
    public String getEventType() {
        return "ErrorLogged";
    }
}
