package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
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
 * All endpoints require JWT authentication.
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
    private final TokenService tokenService;

    public BatchController(BatchLifecycleService batchLifecycleService, TokenService tokenService) {
        this.batchLifecycleService = batchLifecycleService;
        this.tokenService = tokenService;
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
    public ResponseEntity<Map<String, Object>> startBatch(
            @RequestHeader("Authorization") String authHeader) {

        try {
            UUID siteId = extractSiteId(authHeader);
            UUID accountId = tokenService.extractAccountId(extractToken(authHeader));
            String domain = tokenService.extractDomain(extractToken(authHeader));

            logger.info("Starting batch: siteId={}, domain={}", siteId, domain);

            Batch batch = batchLifecycleService.startBatch(accountId, siteId, domain);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

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
            @PathVariable("id") UUID batchId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            logger.info("Completing batch: batchId={}", batchId);

            Batch batch = batchLifecycleService.completeBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

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
            @PathVariable("id") UUID batchId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            logger.info("Failing batch: batchId={}", batchId);

            Batch batch = batchLifecycleService.failBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

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
            @PathVariable("id") UUID batchId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            logger.info("Cancelling batch: batchId={}", batchId);

            Batch batch = batchLifecycleService.cancelBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

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
            @PathVariable("id") UUID batchId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            Batch batch = batchLifecycleService.getBatch(batchId);

            Map<String, Object> response = createBatchResponse(batch);
            return ResponseEntity.ok(response);

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

    private UUID extractSiteId(String authHeader) {
        String token = extractToken(authHeader);
        return tokenService.validateToken(token);
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid Authorization header");
        }
        return authHeader.substring("Bearer ".length());
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
