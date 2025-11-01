package com.bitbi.dfm.batch.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when a batch cannot be found.
 * <p>
 * This is a domain exception indicating that the requested batch
 * does not exist in the system.
 * </p>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 */
public class BatchNotFoundException extends RuntimeException {

    private final UUID batchId;

    /**
     * Constructs a new BatchNotFoundException with the specified batch ID.
     *
     * @param batchId the ID of the batch that was not found
     */
    public BatchNotFoundException(UUID batchId) {
        super(String.format("Batch not found with ID: %s", batchId));
        this.batchId = batchId;
    }

    /**
     * Constructs a new BatchNotFoundException with the specified batch ID and cause.
     *
     * @param batchId the ID of the batch that was not found
     * @param cause   the cause of the exception
     */
    public BatchNotFoundException(UUID batchId, Throwable cause) {
        super(String.format("Batch not found with ID: %s", batchId), cause);
        this.batchId = batchId;
    }

    /**
     * Returns the ID of the batch that was not found.
     *
     * @return the batch ID
     */
    public UUID getBatchId() {
        return batchId;
    }
}
