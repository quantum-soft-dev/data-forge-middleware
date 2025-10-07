package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base interface for all domain events.
 * <p>
 * Domain events represent significant occurrences within the domain
 * that other parts of the system may need to react to.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface DomainEvent {

    /**
     * Get unique event identifier.
     *
     * @return event ID
     */
    UUID eventId();

    /**
     * Get timestamp when event occurred.
     *
     * @return occurrence timestamp
     */
    LocalDateTime occurredAt();

    /**
     * Get event type name.
     *
     * @return event type
     */
    String getEventType();
}
