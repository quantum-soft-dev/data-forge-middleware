package com.bitbi.dfm.clientlog.presentation;

import com.bitbi.dfm.clientlog.application.ClientDiagnosticLogService;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;
import com.bitbi.dfm.clientlog.presentation.dto.ClientLogResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST controller for client diagnostic log upload (Device API).
 * <p>
 * Accepts multipart log file uploads with metadata from client devices.
 * Authentication: Custom JWT (HMAC-SHA256) via JwtAuthenticationFilter.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US4 - Client Uploads Diagnostic Logs
 */
@RestController
@Tag(name = "Device - Diagnostic Logs", description = "Client diagnostic log upload endpoints")
public class ClientLogDeviceController {

    private static final Logger logger = LoggerFactory.getLogger(ClientLogDeviceController.class);

    private final ClientDiagnosticLogService logService;
    private final AuthorizationHelper authorizationHelper;

    public ClientLogDeviceController(ClientDiagnosticLogService logService, AuthorizationHelper authorizationHelper) {
        this.logService = logService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Upload client diagnostic log file.
     * <p>
     * POST /api/v1/device/logs
     * </p>
     * <p>
     * Accepts .log, .log.gz, .txt, .txt.gz files up to 10MB.
     * Max 10 uploads per site per day. 30-day retention.
     * </p>
     *
     * @param file          log file (required)
     * @param clientVersion client application version
     * @param os            client operating system
     * @param periodFrom    log period start (ISO 8601)
     * @param periodTo      log period end (ISO 8601)
     * @param tags          comma-separated tags
     * @param description   problem description
     * @return 201 Created with log metadata
     */
    @Operation(
            summary = "Upload client diagnostic logs",
            description = "Upload client log files for remote troubleshooting. " +
                    "Accepts .log, .log.gz, .txt, .txt.gz files up to 10MB. " +
                    "Max 10 uploads per site per day."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Log uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid file type or empty file"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing JWT token"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (10 uploads per site per day)")
    })
    @PostMapping(value = ApiRoutes.DEVICE_LOGS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClientLogResponseDto> uploadLog(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "clientVersion", required = false) String clientVersion,
            @RequestParam(value = "os", required = false) String os,
            @RequestParam(value = "periodFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime periodFrom,
            @RequestParam(value = "periodTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime periodTo,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "description", required = false) String description
    ) {
        UUID siteId = authorizationHelper.getAuthenticatedSiteId();
        UUID accountId = authorizationHelper.getAuthenticatedAccountId();

        logger.debug("Client log upload: siteId={}, filename={}", siteId, file.getOriginalFilename());

        ClientDiagnosticLog log = logService.uploadLog(
                siteId, accountId, file,
                clientVersion, os, periodFrom, periodTo, tags, description
        );

        ClientLogResponseDto response = ClientLogResponseDto.fromEntity(log);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
