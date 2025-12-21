package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginAdminQueryService;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.presentation.dto.PluginAuditLogEntryDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginAuditLogPageResponseDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginConfigResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin REST controller for plugin management operations.
 *
 * <p>Implements FR-013 requirements:</p>
 * <ul>
 *   <li>GET /api/v1/admin/plugins - List all registered plugins</li>
 *   <li>GET /api/v1/admin/plugins/audit - Query plugin audit logs with filters</li>
 * </ul>
 *
 * <p>Requires ROLE_ADMIN for all operations (configured in SecurityConfiguration).</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@RestController
@RequestMapping("/api/v1/admin/plugins")
@Tag(name = "Plugin Administration", description = "Admin endpoints for plugin management and audit")
@SecurityRequirement(name = "oauth2")
public class PluginAdminController {

    private static final Logger log = LoggerFactory.getLogger(PluginAdminController.class);

    private final PluginAdminQueryService pluginAdminQueryService;

    public PluginAdminController(PluginAdminQueryService pluginAdminQueryService) {
        this.pluginAdminQueryService = pluginAdminQueryService;
    }

    /**
     * Lists all registered plugins with their configuration.
     *
     * @return list of registered plugin configurations
     */
    @GetMapping
    @Operation(
            summary = "List registered plugins",
            description = "Returns all plugins registered in the system with their configuration and supported events"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of registered plugins",
                    content = @Content(schema = @Schema(implementation = PluginConfigResponseDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)")
    })
    public ResponseEntity<List<PluginConfigResponseDto>> listRegisteredPlugins() {
        log.debug("Admin request: list registered plugins");

        List<PluginConfigResponseDto> plugins = pluginAdminQueryService.listRegisteredPlugins();

        log.info("Listed {} registered plugins", plugins.size());
        return ResponseEntity.ok(plugins);
    }

    /**
     * Queries plugin audit logs with optional filters.
     *
     * @param pluginId   filter by plugin ID (optional)
     * @param accountId  filter by account ID (optional)
     * @param actionType filter by action type (optional)
     * @param success    filter by success status (optional)
     * @param from       start of date range (optional)
     * @param to         end of date range (optional)
     * @param page       page number (0-indexed)
     * @param size       page size
     * @return paginated audit log entries
     */
    @GetMapping("/audit")
    @Operation(
            summary = "Query plugin audit logs",
            description = """
                    Returns paginated audit logs for plugin operations.

                    Supports filtering by:
                    - pluginId: specific plugin
                    - accountId: specific account
                    - actionType: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT
                    - success: true/false
                    - from/to: date range (ISO 8601 format)

                    **Privacy**: Request bodies are stored as SHA-256 hashes only (FR-014)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated audit log entries",
                    content = @Content(schema = @Schema(implementation = PluginAuditLogPageResponseDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)")
    })
    public ResponseEntity<PluginAuditLogPageResponseDto> queryAuditLogs(
            @Parameter(description = "Filter by plugin ID")
            @RequestParam(required = false) String pluginId,

            @Parameter(description = "Filter by account ID")
            @RequestParam(required = false) UUID accountId,

            @Parameter(description = "Filter by action type")
            @RequestParam(required = false) PluginActionType actionType,

            @Parameter(description = "Filter by success status")
            @RequestParam(required = false) Boolean success,

            @Parameter(description = "Start of date range (inclusive, ISO 8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "End of date range (exclusive, ISO 8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "50") int size) {

        log.debug("Admin request: query audit logs - pluginId={}, accountId={}, actionType={}, success={}, from={}, to={}, page={}, size={}",
                pluginId, accountId, actionType, success, from, to, page, size);

        // Enforce maximum page size
        int effectiveSize = Math.min(size, 100);

        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "occurredAt"));

        Page<PluginAuditLogEntryDto> auditLogs = pluginAdminQueryService.queryAuditLogs(
                pluginId, accountId, actionType, success, from, to, pageable);

        log.info("Returned {} audit log entries (page {}/{})",
                auditLogs.getNumberOfElements(), page, auditLogs.getTotalPages());

        return ResponseEntity.ok(PluginAuditLogPageResponseDto.fromPage(auditLogs));
    }

    /**
     * Gets audit logs for a specific plugin.
     *
     * @param pluginId the plugin identifier
     * @param page     page number
     * @param size     page size
     * @return paginated audit log entries
     */
    @GetMapping("/{pluginId}/audit")
    @Operation(
            summary = "Get audit logs for a plugin",
            description = "Returns paginated audit logs for a specific plugin"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated audit log entries for the plugin",
                    content = @Content(schema = @Schema(implementation = PluginAuditLogPageResponseDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)")
    })
    public ResponseEntity<PluginAuditLogPageResponseDto> getPluginAuditLogs(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "50") int size) {

        log.debug("Admin request: get audit logs for plugin {} - page={}, size={}", pluginId, page, size);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "occurredAt"));

        Page<PluginAuditLogEntryDto> auditLogs = pluginAdminQueryService.getAuditLogsByPlugin(pluginId, pageable);

        log.info("Returned {} audit log entries for plugin {} (page {}/{})",
                auditLogs.getNumberOfElements(), pluginId, page, auditLogs.getTotalPages());

        return ResponseEntity.ok(PluginAuditLogPageResponseDto.fromPage(auditLogs));
    }
}
