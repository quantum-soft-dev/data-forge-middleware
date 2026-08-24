package com.bitbi.dfm.shared.domain.events;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Domain event fired when a batch expires due to timeout.
 *
 * <p>Extended to include accountId for plugin event dispatch (Phase 7 - US5).
 * The accountId is nullable for backward compatibility with existing code.</p>
 */
public record BatchExpiredEvent(
        UUID eventId,
        LocalDateTime occurredAt,
        UUID batchId,
        UUID accountId
) implements DomainEvent {

    /**
     * Creates a BatchExpiredEvent with accountId (preferred constructor for plugin dispatch).
     *
     * @param batchId the batch identifier
     * @param accountId the account identifier (required for plugin event dispatch)
     */
    public BatchExpiredEvent(UUID batchId, UUID accountId) {
        this(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC), batchId, accountId);
    }

    /**
     * Legacy constructor without accountId (for backward compatibility).
     *
     * @param batchId the batch identifier
     * @deprecated Use constructor with accountId for plugin event support
     */
    @Deprecated
    public BatchExpiredEvent(UUID batchId) {
        this(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC), batchId, null);
    }

    @Override
    public String getEventType() {
        return "BatchExpired";
    }
}
