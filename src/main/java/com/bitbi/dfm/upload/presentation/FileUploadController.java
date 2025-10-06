package com.bitbi.dfm.upload.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.upload.application.FileUploadService;
import com.bitbi.dfm.upload.domain.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for file upload operations.
 * <p>
 * Handles multipart file uploads to batch with S3 storage integration.
 * Requires JWT authentication.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/batch")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final FileUploadService fileUploadService;
    private final TokenService tokenService;

    public FileUploadController(FileUploadService fileUploadService, TokenService tokenService) {
        this.fileUploadService = fileUploadService;
        this.tokenService = tokenService;
    }

    /**
     * Upload file to batch.
     * <p>
     * POST /api/v1/batch/{id}/upload
     * Content-Type: multipart/form-data
     * </p>
     *
     * @param batchId    batch identifier
     * @param file       multipart file to upload
     * @param authHeader Authorization header with Bearer token
     * @return uploaded file metadata response
     */
    @PostMapping("/{id}/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @PathVariable("id") UUID batchId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            logger.info("Uploading file to batch: batchId={}, filename={}, size={} bytes",
                       batchId, file.getOriginalFilename(), file.getSize());

            if (file.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("File cannot be empty"));
            }

            UploadedFile uploadedFile = fileUploadService.uploadFile(batchId, file);

            Map<String, Object> response = createFileResponse(uploadedFile);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (FileUploadService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Batch not found"));

        } catch (FileUploadService.InvalidBatchStatusException e) {
            logger.warn("Invalid batch status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse("Batch is not accepting uploads"));

        } catch (FileUploadService.DuplicateFileException e) {
            logger.warn("Duplicate filename: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse(e.getMessage()));

        } catch (FileUploadService.FileSizeExceededException e) {
            logger.warn("File size exceeded: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(createErrorResponse(e.getMessage()));

        } catch (FileUploadService.InvalidFileException e) {
            logger.warn("Invalid file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error uploading file: batchId={}, filename={}",
                       batchId, file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to upload file"));
        }
    }

    /**
     * Get uploaded file metadata.
     * <p>
     * GET /api/v1/batch/{batchId}/files/{fileId}
     * </p>
     *
     * @param batchId    batch identifier
     * @param fileId     file identifier
     * @param authHeader Authorization header
     * @return file metadata response
     */
    @GetMapping("/{batchId}/files/{fileId}")
    public ResponseEntity<Map<String, Object>> getFile(
            @PathVariable("batchId") UUID batchId,
            @PathVariable("fileId") UUID fileId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            UploadedFile file = fileUploadService.getFile(fileId);

            // Verify file belongs to batch
            if (!file.getBatchId().equals(batchId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(createErrorResponse("File not found in batch"));
            }

            Map<String, Object> response = createFileResponse(file);
            return ResponseEntity.ok(response);

        } catch (FileUploadService.FileNotFoundException e) {
            logger.warn("File not found: {}", fileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("File not found"));

        } catch (Exception e) {
            logger.error("Error getting file: fileId={}", fileId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve file"));
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

    private Map<String, Object> createFileResponse(UploadedFile file) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", file.getId());
        response.put("batchId", file.getBatchId());
        response.put("originalFileName", file.getOriginalFileName());
        response.put("s3Key", file.getS3Key());
        response.put("fileSize", file.getFileSize());
        response.put("contentType", file.getContentType());
        response.put("checksum", file.getChecksum());
        response.put("uploadedAt", file.getUploadedAt().toString());
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
