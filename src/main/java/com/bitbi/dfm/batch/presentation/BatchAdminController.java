package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailResponseDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
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

import java.util.List;
import java.util.UUID;

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
    public ResponseEntity<PageResponseDto<BatchSummaryDto>> listBatches(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

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

        PageResponseDto<BatchSummaryDto> response = PageResponseDto.of(batchPage, BatchSummaryDto::fromEntity);

        return ResponseEntity.ok(response);
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
    public ResponseEntity<BatchDetailResponseDto> getBatchDetails(@PathVariable("id") UUID batchId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found"));

        Site site = siteRepository.findById(batch.getSiteId())
                .orElse(null);

        List<UploadedFile> files = uploadedFileRepository.findByBatchId(batchId);

        BatchDetailResponseDto response = BatchDetailResponseDto.fromEntityAndFiles(batch, site, files);
        return ResponseEntity.ok(response);
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
        logger.info("Deleting batch: batchId={}", batchId);

        if (!batchRepository.existsById(batchId)) {
            return ResponseEntity.notFound().build();
        }

        batchRepository.deleteById(batchId);
        return ResponseEntity.noContent().build();
    }
}
