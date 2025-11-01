package com.bitbi.dfm.batch.infrastructure;

import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Projection interface for batch list queries with file count.
 * <p>
 * Avoids N+1 queries by using materialized uploadedFilesCount column
 * instead of JOIN or COUNT subquery.
 * </p>
 * <p>
 * Usage in repository queries:
 * </p>
 * <pre>
 * {@code
 * @Query("""
 *     SELECT b.id as id, b.siteId as siteId, b.status as status,
 *            b.hasErrors as hasErrors, b.startedAt as startedAt,
 *            b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
 *            b.totalSize as totalSize
 *     FROM Batch b
 *     WHERE b.siteId IN :siteIds
 *     ORDER BY b.startedAt DESC, b.id DESC
 *     """)
 * List<BatchWithFileCountProjection> findBySiteIdsFirstPage(...);
 * }
 * </pre>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.infrastructure.JpaBatchRepository
 */
public interface BatchWithFileCountProjection {

    /**
     * Batch unique identifier.
     *
     * @return Batch ID
     */
    UUID getId();

    /**
     * Site identifier.
     *
     * @return Site ID
     */
    UUID getSiteId();

    /**
     * Batch status (IN_PROGRESS, COMPLETED, etc.).
     *
     * @return Status string
     */
    String getStatus();

    /**
     * Whether batch has any errors.
     *
     * @return True if errors exist
     */
    Boolean getHasErrors();

    /**
     * Batch start timestamp.
     *
     * @return Started at timestamp
     */
    LocalDateTime getStartedAt();

    /**
     * Batch completion timestamp (null if in progress).
     *
     * @return Completed at timestamp
     */
    LocalDateTime getCompletedAt();

    /**
     * Number of files uploaded (from materialized column).
     * <p>
     * Uses @Value annotation to map from uploadedFilesCount column.
     * </p>
     *
     * @return File count
     */
    @Value("#{target.uploadedFilesCount}")
    Integer getFileCount();

    /**
     * Total size of all files in bytes.
     *
     * @return Total size in bytes
     */
    @Value("#{target.totalSize}")
    Long getTotalSize();
}
