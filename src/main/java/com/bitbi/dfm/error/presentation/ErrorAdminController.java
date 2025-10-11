package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.presentation.dto.ErrorLogSummaryDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for error log administration (Admin UI API).
 * <p>
 * Provides admin endpoints for error log viewing and export.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 * <p>
 * URL change from v2.x: /admin/errors → /api/admin/errors (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@RequestMapping("/api/admin/errors")
@PreAuthorize("hasRole('ADMIN')")
public class ErrorAdminController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorAdminController.class);
    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ErrorLogRepository errorLogRepository;
    private final ObjectMapper objectMapper;

    public ErrorAdminController(ErrorLogRepository errorLogRepository, ObjectMapper objectMapper) {
        this.errorLogRepository = errorLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * List error logs with filtering and pagination.
     * <p>
     * GET /admin/errors?siteId={siteId}&type={type}&page=0&size=20
     * </p>
     *
     * @param siteId optional site filter
     * @param type optional error type filter
     * @param page page number
     * @param size page size
     * @return paginated list of error logs
     */
    @GetMapping
    public ResponseEntity<PageResponseDto<ErrorLogSummaryDto>> listErrors(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<ErrorLog> errorPage;

        if (siteId != null && type != null) {
            errorPage = errorLogRepository.findBySiteIdAndType(siteId, type, pageable);
        } else if (siteId != null) {
            errorPage = errorLogRepository.findBySiteId(siteId, pageable);
        } else if (type != null) {
            errorPage = errorLogRepository.findByType(type, pageable);
        } else {
            errorPage = errorLogRepository.findAll(pageable);
        }

        PageResponseDto<ErrorLogSummaryDto> response = PageResponseDto.of(errorPage, ErrorLogSummaryDto::fromEntity);

        return ResponseEntity.ok(response);
    }

    /**
     * Export error logs to CSV.
     * <p>
     * GET /admin/errors/export?siteId={siteId}&type={type}&start={start}&end={end}
     * </p>
     *
     * @param siteId optional site filter
     * @param type optional error type filter
     * @param start optional start date filter
     * @param end optional end date filter
     * @return CSV file download
     */
    @GetMapping("/export")
    public ResponseEntity<String> exportErrors(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDateTime startDate = start != null ? LocalDateTime.parse(start) : null;
        LocalDateTime endDate = end != null ? LocalDateTime.parse(end) : null;

        List<ErrorLog> errors = errorLogRepository.exportByFilters(siteId, type, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("ID,Batch ID,Site ID,Type,Message,Metadata,Occurred At\n");

        for (ErrorLog error : errors) {
            csv.append(escapeCSV(error.getId().toString())).append(",");
            csv.append(escapeCSV(error.getBatchId() != null ? error.getBatchId().toString() : "")).append(",");
            csv.append(escapeCSV(error.getSiteId().toString())).append(",");
            csv.append(escapeCSV(error.getType())).append(",");
            csv.append(escapeCSV(error.getMessage())).append(",");
            csv.append(escapeCSV(serializeMetadata(error.getMetadata()))).append(",");
            csv.append(escapeCSV(error.getOccurredAt().format(CSV_DATE_FORMAT))).append("\n");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "error-logs.csv");

        return ResponseEntity.ok()
                .headers(headers)
                .body(csv.toString());
    }

    private String escapeCSV(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            logger.warn("Failed to serialize metadata: {}", e.getMessage());
            return "";
        }
    }
}
