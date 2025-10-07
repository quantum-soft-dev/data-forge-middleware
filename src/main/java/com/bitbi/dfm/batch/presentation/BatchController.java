package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for batch lifecycle operations.
 * <p>
 * Provides endpoints for batch management: start, complete, fail, cancel.
 * All endpoints require JWT authentication and verify site ownership.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/batch")
public class BatchController {

    private static final Logger logger = LoggerFactory.getLogger(BatchController.class);

    private final BatchLifecycleService batchLifecycleService;
    private final AuthorizationHelper authorizationHelper;

    public BatchController(BatchLifecycleService batchLifecycleService, AuthorizationHelper authorizationHelper) {
        this.batchLifecycleService = batchLifecycleService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Start new batch.
     * <p>
     * POST /api/v1/batch/start
     * Requires JWT Bearer token.
     * </p>
     *
     * @param authHeader Authorization header with Bearer token
     * @return created batch response
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBatch() {

        try {
            // Get authenticated site/account/domain from security context
            UUID siteId = authorizationHelper.getAuthenticatedSiteId();
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            String domain = authorizationHelper.getAuthenticatedDomain();

            logger.info("Starting batch: siteId={}, domain={}", siteId, domain);

            Batch batch = batchLifecycleService.startBatch(accountId, siteId, domain);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch start: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchLifecycleService.ActiveBatchExistsException e) {
            logger.warn("Active batch exists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse(HttpStatus.CONFLICT, "Site already has an active batch"));

        } catch (BatchLifecycleService.ConcurrentBatchLimitException e) {
            logger.warn("Concurrent batch limit exceeded: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(createErrorResponse(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));

        } catch (Exception e) {
            logger.error("Error starting batch", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to start batch"));
        }
    }

    /**
     * Complete batch successfully.
     * <p>
     * POST /api/v1/batch/{id}/complete
     * </p>
     *
     * @param batchId    batch identifier
     * @param authHeader Authorization header
     * @return completed batch response
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeBatch(
            @PathVariable("id") UUID batchId) {

        try {
            logger.info("Completing batch: batchId={}", batchId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            batch = batchLifecycleService.completeBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch completion: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Invalid batch status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            logger.error("Error completing batch: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to complete batch"));
        }
    }

    /**
     * Fail batch with error.
     * <p>
     * POST /api/v1/batch/{id}/fail
     * </p>
     *
     * @param batchId    batch identifier
     * @param authHeader Authorization header
     * @return failed batch response
     */
    @PostMapping("/{id}/fail")
    public ResponseEntity<Map<String, Object>> failBatch(
            @PathVariable("id") UUID batchId) {

        try {
            logger.info("Failing batch: batchId={}", batchId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            batch = batchLifecycleService.failBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch fail: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Invalid batch status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            logger.error("Error failing batch: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fail batch"));
        }
    }

    /**
     * Cancel batch (user-initiated).
     * <p>
     * POST /api/v1/batch/{id}/cancel
     * </p>
     *
     * @param batchId    batch identifier
     * @param authHeader Authorization header
     * @return cancelled batch response
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelBatch(
            @PathVariable("id") UUID batchId) {

        try {
            logger.info("Cancelling batch: batchId={}", batchId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            batch = batchLifecycleService.cancelBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch cancel: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Invalid batch status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            logger.error("Error cancelling batch: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to cancel batch"));
        }
    }

    /**
     * Get batch status.
     * <p>
     * GET /api/v1/batch/{id}
     * </p>
     *
     * @param batchId    batch identifier
     * @param authHeader Authorization header
     * @return batch response
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBatch(
            @PathVariable("id") UUID batchId) {

        try {
            // Get batch
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch access: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (Exception e) {
            logger.error("Error getting batch: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve batch"));
        }
    }

    private Map<String, Object> createBatchResponse(Batch batch) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", batch.getId());
        response.put("batchId", batch.getId());
        response.put("siteId", batch.getSiteId());
        response.put("status", batch.getStatus().name());
        response.put("s3Path", batch.getS3Path());
        response.put("uploadedFilesCount", batch.getUploadedFilesCount());
        response.put("totalSize", batch.getTotalSize());
        response.put("hasErrors", batch.getHasErrors());
        response.put("startedAt", batch.getStartedAt().toString());
        if (batch.getCompletedAt() != null) {
            response.put("completedAt", batch.getCompletedAt().toString());
        }
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        return createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }
}
