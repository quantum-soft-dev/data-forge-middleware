package com.bitbi.dfm.upload.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.upload.application.FileUploadService;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.presentation.dto.FileUploadResponseDto;
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
 * REST controller for file upload operations (Data Forge Client API).
 * <p>
 * Handles multipart file uploads to batch with S3 storage integration.
 * Requires JWT authentication.
 * </p>
 * <p>
 * URL change from v2.x: /api/v1/batch → /api/dfc/batch (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@RequestMapping("/api/dfc/batch")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final FileUploadService fileUploadService;
    private final TokenService tokenService;

    public FileUploadController(FileUploadService fileUploadService, TokenService tokenService) {
        this.fileUploadService = fileUploadService;
        this.tokenService = tokenService;
    }

    /**
     * Upload files to active batch.
     * <p>
     * POST /api/dfc/batch/{batchId}/upload
     * Content-Type: multipart/form-data
     * Uploads files to the authenticated site's active (IN_PROGRESS) batch.
     * </p>
     *
     * @param batchId    batch identifier
     * @param files      multipart files to upload
     * @param authHeader Authorization header with Bearer token
     * @return upload summary response
     */
    @PostMapping("/{batchId}/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @PathVariable("batchId") UUID batchId,
            @RequestParam("files") MultipartFile[] files,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            logger.info("Uploading {} files to batch: batchId={}", files.length, batchId);

            if (files.length == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse(HttpStatus.BAD_REQUEST, "No files provided"));
            }

            java.util.List<Map<String, Object>> uploadedFiles = new java.util.ArrayList<>();

            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(createErrorResponse(HttpStatus.BAD_REQUEST, "File cannot be empty"));
                }

                UploadedFile uploadedFile = fileUploadService.uploadFile(batchId, file);

                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("fileName", uploadedFile.getOriginalFileName());
                fileInfo.put("fileSize", uploadedFile.getFileSize());
                fileInfo.put("uploadedAt", uploadedFile.getUploadedAt().toString());
                uploadedFiles.add(fileInfo);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("uploadedFiles", uploadedFiles.size());
            response.put("files", uploadedFiles);

            return ResponseEntity.ok(response);

        } catch (FileUploadService.BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (FileUploadService.InvalidBatchStatusException e) {
            logger.warn("Invalid batch status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, "Cannot upload files to completed batch"));

        } catch (FileUploadService.DuplicateFileException e) {
            logger.warn("Duplicate filename: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage()));

        } catch (FileUploadService.FileSizeExceededException e) {
            logger.warn("File size exceeded: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(createErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage()));

        } catch (FileUploadService.InvalidFileException e) {
            logger.warn("Invalid file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            logger.error("Error uploading files: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload files"));
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
    public ResponseEntity<?> getFile(
            @PathVariable("batchId") UUID batchId,
            @PathVariable("fileId") UUID fileId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            UploadedFile file = fileUploadService.getFile(fileId);

            // Verify file belongs to batch
            if (!file.getBatchId().equals(batchId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(createErrorResponse(HttpStatus.NOT_FOUND, "File not found in batch"));
            }

            FileUploadResponseDto response = FileUploadResponseDto.fromEntity(file);
            return ResponseEntity.ok(response);

        } catch (FileUploadService.FileNotFoundException e) {
            logger.warn("File not found: {}", fileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "File not found"));

        } catch (Exception e) {
            logger.error("Error getting file: fileId={}", fileId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve file"));
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

    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }
}
