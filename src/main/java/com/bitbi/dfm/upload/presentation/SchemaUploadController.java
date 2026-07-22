package com.bitbi.dfm.upload.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.site.application.ClientApiVersionGuard;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.upload.presentation.dto.SchemaResponseDto;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for site schema upload (Data Forge Client API).
 *
 * <p>Allows CDC sites to register/update their table schemas before submitting batches.
 * The schema is used by the server for precise PK-based SQL generation from JSONL delta files.</p>
 *
 * <p>Authentication: Custom JWT (same as batch upload, /api/dfc/** filter chain).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/dfc/schema")
@Tag(name = "Schema Upload", description = "Site table schema management for CDC sites")
public class SchemaUploadController {

    private static final Logger logger = LoggerFactory.getLogger(SchemaUploadController.class);

    private final SiteSchemaService siteSchemaService;
    private final TokenService tokenService;
    private final ClientApiVersionGuard clientApiVersionGuard;

    public SchemaUploadController(SiteSchemaService siteSchemaService, TokenService tokenService,
                                  ClientApiVersionGuard clientApiVersionGuard) {
        this.siteSchemaService = siteSchemaService;
        this.tokenService = tokenService;
        this.clientApiVersionGuard = clientApiVersionGuard;
    }

    /**
     * Upload or update table schema for the authenticated site.
     *
     * <p>POST /api/dfc/schema
     * Content-Type: application/json</p>
     *
     * <p>For POSTGRES_CDC sites: required before first batch.
     * For DBF sites: optional; stored but not required for SQL generation.</p>
     *
     * @param request    schema upload request with table definitions
     * @param authHeader Authorization header with Bearer JWT token
     * @return schema response with siteId, version, and update timestamp
     */
    @PostMapping
    @Operation(summary = "Upload or update table schema", description = "Submit table schema for CDC-based SQL generation. Required for POSTGRES_CDC sites before first batch.")
    public ResponseEntity<?> uploadSchema(
            @RequestBody @Valid SchemaUploadRequestDto request,
            @RequestHeader("Authorization") String authHeader) {

        UUID siteId;
        try {
            siteId = extractSiteId(authHeader);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse(HttpStatus.UNAUTHORIZED, "Invalid or missing authorization token"));
        }

        try {
            clientApiVersionGuard.assertHttpFileApiAllowed(siteId);

            logger.info("Schema upload request: siteId={}, tables={}", siteId, request.tables().keySet());

            SiteSchema saved = siteSchemaService.upsertSchema(siteId, request);
            SchemaResponseDto response = SchemaResponseDto.fromEntity(saved);

            logger.info("Schema saved: siteId={}, version={}", siteId, saved.getSchemaVersion());
            return ResponseEntity.ok(response);

        } catch (ClientApiVersionGuard.HttpFileApiDisabledException e) {
            logger.warn("HTTP file API disabled for V2 site: {}", e.getMessage());
            Map<String, Object> error = createErrorResponse(HttpStatus.CONFLICT, e.getMessage());
            error.put("code", ClientApiVersionGuard.ERROR_CODE);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);

        } catch (SiteSchemaService.InvalidSchemaException e) {
            logger.warn("Invalid schema upload: siteId={}, error={}", siteId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, "Invalid schema: " + e.getMessage()));

        } catch (Exception e) {
            logger.error("Error saving schema: siteId={}", siteId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save schema"));
        }
    }

    private UUID extractSiteId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid Authorization header");
        }
        String token = authHeader.substring("Bearer ".length());
        return tokenService.validateToken(token);
    }

    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }
}
