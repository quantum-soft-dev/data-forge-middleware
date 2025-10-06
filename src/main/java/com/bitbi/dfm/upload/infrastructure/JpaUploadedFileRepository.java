package com.bitbi.dfm.upload.infrastructure;

import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
