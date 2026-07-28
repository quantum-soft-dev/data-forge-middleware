package com.bitbi.dfm.upload.application;

import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only application service for historical uploaded-file metadata.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class FileUploadService {

    private final UploadedFileRepository uploadedFileRepository;

    public FileUploadService(UploadedFileRepository uploadedFileRepository) {
        this.uploadedFileRepository = uploadedFileRepository;
    }

    /**
     * Get uploaded file by ID.
     *
     * @param fileId file identifier
     * @return uploaded file
     * @throws FileNotFoundException if file not found
     */
    @Transactional(readOnly = true)
    public UploadedFile getFile(UUID fileId) {
        return uploadedFileRepository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));
    }

    /**
     * List all files for batch.
     *
     * @param batchId batch identifier
     * @return list of uploaded files
     */
    @Transactional(readOnly = true)
    public List<UploadedFile> listFilesByBatch(UUID batchId) {
        return uploadedFileRepository.findByBatchId(batchId);
    }

    /**
     * Get total upload count for batch.
     *
     * @param batchId batch identifier
     * @return number of uploaded files
     */
    @Transactional(readOnly = true)
    public long countFilesByBatch(UUID batchId) {
        return uploadedFileRepository.countByBatchId(batchId);
    }

    /**
     * Exception thrown when file is not found.
     */
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }

}
