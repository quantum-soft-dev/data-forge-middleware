package com.bitbi.dfm.upload.infrastructure;

import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of UploadedFileRepository.
 * <p>
 * Includes duplicate filename check within batch scope.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaUploadedFileRepository extends JpaRepository<UploadedFile, UUID>, UploadedFileRepository {

    /**
     * Find all files for given batch.
     *
     * @param batchId batch identifier
     * @return list of uploaded files
     */
    @Query("SELECT f FROM UploadedFile f WHERE f.batchId = :batchId ORDER BY f.uploadedAt ASC")
    List<UploadedFile> findByBatchId(UUID batchId);

    /**
     * Check if file with given name already exists in batch.
     * <p>
     * Used to enforce filename uniqueness per batch.
     * </p>
     *
     * @param batchId batch identifier
     * @param fileName original file name
     * @return true if file exists
     */
    @Query("SELECT COUNT(f) > 0 FROM UploadedFile f WHERE f.batchId = :batchId AND f.originalFileName = :fileName")
    boolean existsByBatchIdAndOriginalFileName(UUID batchId, String fileName);

    /**
     * Count files by batch ID.
     *
     * @param batchId batch identifier
     * @return number of files
     */
    @Query("SELECT COUNT(f) FROM UploadedFile f WHERE f.batchId = :batchId")
    long countByBatchId(UUID batchId);

    /**
     * Count files by account ID (joins through batches table).
     *
     * @param accountId account identifier
     * @return number of files
     */
    @Query("SELECT COUNT(f) FROM UploadedFile f JOIN Batch b ON f.batchId = b.id WHERE b.accountId = :accountId")
    long countByAccountId(UUID accountId);

    /**
     * Count files by site ID (joins through batches table).
     *
     * @param siteId site identifier
     * @return number of files
     */
    @Query("SELECT COUNT(f) FROM UploadedFile f JOIN Batch b ON f.batchId = b.id WHERE b.siteId = :siteId")
    long countBySiteId(UUID siteId);

    /**
     * Find S3 keys and sizes for files in a batch.
     *
     * @param batchId batch identifier
     * @return list of key/size projections
     */
    @Query("SELECT f.s3Key AS s3Key, f.fileSize AS fileSize FROM UploadedFile f WHERE f.batchId = :batchId")
    List<FileKeySize> findS3KeysByBatchId(UUID batchId);

    /**
     * Finds the latest uploaded file for each unique original file name for a given account.
     * Uses a subquery to find the maximum uploaded_at per file name.
     *
     * @param accountId account identifier
     * @return list of latest file info per unique file name
     */
    @Query(value = """
        WITH latest_uploads AS (
            SELECT
                uf.original_file_name,
                MAX(uf.uploaded_at) AS max_uploaded_at
            FROM uploaded_files uf
            JOIN batches b ON uf.batch_id = b.id
            WHERE b.account_id = :accountId
            GROUP BY uf.original_file_name
        )
        SELECT uf.original_file_name AS originalFileName, uf.file_size AS fileSize, uf.uploaded_at AS uploadedAt
        FROM uploaded_files uf
        JOIN latest_uploads lu ON uf.original_file_name = lu.original_file_name
            AND uf.uploaded_at = lu.max_uploaded_at
        JOIN batches b ON uf.batch_id = b.id
        WHERE b.account_id = :accountId
        ORDER BY uf.original_file_name
        """, nativeQuery = true)
    List<LatestFileInfo> findLatestByOriginalFileNameForAccount(UUID accountId);

    /**
     * Finds the latest uploaded file for each unique original file name for a given site.
     * Includes S3 key for file download operations.
     *
     * @param siteId site identifier
     * @return list of latest file info with S3 key per unique file name
     */
    @Query(value = """
        WITH latest_uploads AS (
            SELECT
                uf.original_file_name,
                MAX(uf.uploaded_at) AS max_uploaded_at
            FROM uploaded_files uf
            JOIN batches b ON uf.batch_id = b.id
            WHERE b.site_id = :siteId
            GROUP BY uf.original_file_name
        )
        SELECT uf.original_file_name AS originalFileName,
               uf.file_size AS fileSize,
               uf.uploaded_at AS uploadedAt,
               uf.s3_key AS s3Key
        FROM uploaded_files uf
        JOIN latest_uploads lu ON uf.original_file_name = lu.original_file_name
            AND uf.uploaded_at = lu.max_uploaded_at
        JOIN batches b ON uf.batch_id = b.id
        WHERE b.site_id = :siteId
        ORDER BY uf.original_file_name
        """, nativeQuery = true)
    List<LatestFileInfoWithS3Key> findLatestByOriginalFileNameForSite(UUID siteId);

    /**
     * Finds a specific file by site ID and original file name.
     * Returns the latest version of the file if multiple versions exist.
     *
     * @param siteId site identifier
     * @param originalFileName original file name to find
     * @return the latest file info with S3 key, or empty if not found
     */
    @Query(value = """
        SELECT uf.original_file_name AS originalFileName,
               uf.file_size AS fileSize,
               uf.uploaded_at AS uploadedAt,
               uf.s3_key AS s3Key
        FROM uploaded_files uf
        JOIN batches b ON uf.batch_id = b.id
        WHERE b.site_id = :siteId
          AND uf.original_file_name = :originalFileName
        ORDER BY uf.uploaded_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<LatestFileInfoWithS3Key> findLatestByOriginalFileNameForSiteAndFileName(UUID siteId, String originalFileName);
}
