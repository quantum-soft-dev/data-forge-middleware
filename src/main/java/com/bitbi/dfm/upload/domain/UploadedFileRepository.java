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
     * Finds the latest uploaded file for each unique original file name for a site.
     * Includes S3 key for file download. Used by Plugin API for CSV file initialization.
     *
     * @param siteId site identifier
     * @return list of latest file info with S3 key per unique file name
     */
    List<LatestFileInfoWithS3Key> findLatestByOriginalFileNameForSite(UUID siteId);

    /**
     * Finds a specific file by site ID and original file name.
     * Returns the latest version of the file if multiple versions exist.
     *
     * @param siteId site identifier
     * @param originalFileName original file name to find
     * @return the latest file info with S3 key, or empty if not found
     */
    Optional<LatestFileInfoWithS3Key> findLatestByOriginalFileNameForSiteAndFileName(UUID siteId, String originalFileName);

    /**
     * Finds S3 keys and sizes for files in a batch.
     *
     * @param batchId batch identifier
     * @return list of S3 key and size projections
     */
    List<FileKeySize> findS3KeysByBatchId(UUID batchId);

    /**
     * Projection interface for latest file info query.
     */
    interface LatestFileInfo {
        String getOriginalFileName();
        Long getFileSize();
        Instant getUploadedAt();
    }

    /**
     * Projection interface for latest file info with S3 key.
     * Used for file download operations.
     */
    interface LatestFileInfoWithS3Key {
        String getOriginalFileName();
        Long getFileSize();
        Instant getUploadedAt();
        String getS3Key();
    }

    /**
     * Projection interface for S3 key and size.
     */
    interface FileKeySize {
        String getS3Key();
        Long getFileSize();
    }
}
