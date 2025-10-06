package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event fired when an account is deactivated.
 * <p>
 * Triggers cascading deactivation of all associated sites.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record AccountDeactivatedEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID accountId
) implements DomainEvent {

    public AccountDeactivatedEvent(UUID accountId) {
        this(UUID.randomUUID(), LocalDateTime.now(), accountId);
    }

    @Override
    public String getEventType() {
        return "AccountDeactivated";
    }
}
