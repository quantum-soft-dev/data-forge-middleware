package com.bitbi.dfm.upload.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for UploadedFile entity.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface UploadedFileRepository {

    Optional<UploadedFile> findById(UUID id);

    List<UploadedFile> findAllById(Iterable<UUID> ids);

    List<UploadedFile> findByBatchId(UUID batchId);

    boolean existsByBatchIdAndOriginalFileName(UUID batchId, String fileName);

    long countByBatchId(UUID batchId);

    long countByAccountId(UUID accountId);

    long countBySiteId(UUID siteId);

    long count();

    UploadedFile save(UploadedFile file);

    void deleteById(UUID id);

    /**
     * Finds the latest uploaded file for each unique original file name.
     * Used by Plugin API to list available tables.
     *
     * @param accountId account identifier
     * @return list of latest file info per unique file name
     */
    List<LatestFileInfo> findLatestByOriginalFileNameForAccount(UUID accountId);

    /**
     * Projection interface for latest file info query.
     */
    interface LatestFileInfo {
        String getOriginalFileName();
        Long getFileSize();
        Instant getUploadedAt();
    }
}
