package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for batch administration (Admin UI API).
 * <p>
 * Provides admin endpoints for batch management operations.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 * <p>
 * URL change from v2.x: /admin/batches → /api/admin/batches (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@RequestMapping("/api/admin/batches")
@PreAuthorize("hasRole('ADMIN')")
public class BatchAdminController {

    private static final Logger logger = LoggerFactory.getLogger(BatchAdminController.class);

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final BatchLifecycleService batchLifecycleService;

    public BatchAdminController(BatchRepository batchRepository,
                                SiteRepository siteRepository,
                                UploadedFileRepository uploadedFileRepository,
                                BatchLifecycleService batchLifecycleService) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.batchLifecycleService = batchLifecycleService;
    }

    /**
     * List batches with filtering and pagination.
     * <p>
     * GET /admin/batches?siteId={siteId}&status={status}&page=0&size=20
     * </p>
     *
     * @param siteId optional site filter
     * @param status optional status filter
     * @param page page number
     * @param size page size
     * @return paginated list of batches
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listBatches(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<Batch> batchPage;

            if (siteId != null && status != null) {
                batchPage = batchRepository.findBySiteIdAndStatus(siteId, BatchStatus.valueOf(status), pageable);
            } else if (siteId != null) {
                batchPage = batchRepository.findBySiteId(siteId, pageable);
            } else if (status != null) {
                batchPage = batchRepository.findByStatus(BatchStatus.valueOf(status), pageable);
            } else {
                batchPage = batchRepository.findAll(pageable);
            }

            List<Map<String, Object>> batchList = batchPage.getContent().stream()
                    .map(this::createBatchSummary)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("content", batchList);
            response.put("page", batchPage.getNumber());
            response.put("size", batchPage.getSize());
            response.put("totalElements", batchPage.getTotalElements());
            response.put("totalPages", batchPage.getTotalPages());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error listing batches", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to list batches"));
        }
    }

    /**
     * Get batch details with files list.
     * <p>
     * GET /admin/batches/{id}
     * </p>
     *
     * @param batchId batch identifier
     * @return batch details with files
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBatchDetails(@PathVariable("id") UUID batchId) {
        try {
            Batch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new RuntimeException("Batch not found"));

            Site site = siteRepository.findById(batch.getSiteId())
                    .orElse(null);

            List<UploadedFile> files = uploadedFileRepository.findByBatchId(batchId);

            Map<String, Object> response = createBatchDetailResponse(batch, site, files);
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Batch not found"));

        } catch (Exception e) {
            logger.error("Error getting batch details: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve batch"));
        }
    }

    /**
     * Delete batch metadata.
     * <p>
     * DELETE /admin/batches/{id}
     * </p>
     *
     * @param batchId batch identifier
     * @return no content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBatch(@PathVariable("id") UUID batchId) {
        try {
            logger.info("Deleting batch: batchId={}", batchId);

            if (!batchRepository.existsById(batchId)) {
                return ResponseEntity.notFound().build();
            }

            batchRepository.deleteById(batchId);
            return ResponseEntity.noContent().build();

        } catch (Exception e) {
            logger.error("Error deleting batch: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private Map<String, Object> createBatchSummary(Batch batch) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", batch.getId());
        summary.put("siteId", batch.getSiteId());
        summary.put("status", batch.getStatus().toString());
        summary.put("s3Path", batch.getS3Path());
        summary.put("uploadedFilesCount", batch.getUploadedFilesCount());
        summary.put("totalSize", batch.getTotalSize());
        summary.put("hasErrors", batch.getHasErrors());
        summary.put("startedAt", batch.getStartedAt() != null ? batch.getStartedAt().toString() : null);
        summary.put("completedAt", batch.getCompletedAt() != null ? batch.getCompletedAt().toString() : null);
        summary.put("createdAt", batch.getCreatedAt().toString());
        return summary;
    }

    private Map<String, Object> createBatchDetailResponse(Batch batch, Site site, List<UploadedFile> files) {
        Map<String, Object> response = createBatchSummary(batch);
        response.put("siteDomain", site != null ? site.getDomain() : "unknown");

        List<Map<String, Object>> fileList = files.stream()
                .map(file -> {
                    Map<String, Object> fileData = new HashMap<>();
                    fileData.put("id", file.getId());
                    fileData.put("originalFileName", file.getOriginalFileName());
                    fileData.put("s3Key", file.getS3Key());
                    fileData.put("fileSize", file.getFileSize());
                    fileData.put("contentType", file.getContentType());
                    fileData.put("checksum", file.getChecksum());
                    fileData.put("uploadedAt", file.getUploadedAt().toString());
                    return fileData;
                })
                .collect(Collectors.toList());

        response.put("files", fileList);
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
