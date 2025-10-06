package com.bitbi.dfm.upload.domain;

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

    List<UploadedFile> findByBatchId(UUID batchId);

    boolean existsByBatchIdAndOriginalFileName(UUID batchId, String fileName);

    UploadedFile save(UploadedFile file);
}
