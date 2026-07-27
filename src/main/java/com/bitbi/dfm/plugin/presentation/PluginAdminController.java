package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginAdminQueryService;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.shared.api.ApiRoutes;
import jakarta.validation.Valid;
import com.bitbi.dfm.plugin.presentation.dto.*;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>Requires ROLE_ADMIN for all operations.</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@RestController
@RequestMapping(ApiRoutes.PLUGINS_ADMIN)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Plugin Administration", description = "Admin endpoints for plugin management and audit")
@SecurityRequirement(name = "oauth2")
public class PluginAdminController {

    private static final Logger log = LoggerFactory.getLogger(PluginAdminController.class);

    private final PluginAdminQueryService pluginAdminQueryService;
    private final PluginHistoryService pluginHistoryService;
    private final SqlGenerationService sqlGenerationService;
    private final AccountPluginRepository accountPluginRepository;

    public PluginAdminController(
            PluginAdminQueryService pluginAdminQueryService,
            PluginHistoryService pluginHistoryService,
            SqlGenerationService sqlGenerationService,
            AccountPluginRepository accountPluginRepository
    ) {
        this.pluginAdminQueryService = pluginAdminQueryService;
        this.pluginHistoryService = pluginHistoryService;
        this.sqlGenerationService = sqlGenerationService;
        this.accountPluginRepository = accountPluginRepository;
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

        // Use unsorted because native SQL query already has ORDER BY clause
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.unsorted());

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

    // ==================== Plugin History Endpoints (Feature 014) ====================

    /**
     * Lists account-plugin activations for a plugin with generation counts.
     * Used in the SQL History tab to select which account to view.
     *
     * @param pluginId the plugin identifier
     * @param page page number
     * @param size page size
     * @return paginated list of account-plugin activations
     */
    @GetMapping("/{pluginId}/account-plugins")
    @Operation(
            summary = "List account-plugins for SQL history",
            description = "Returns paginated list of accounts with active plugin activation and their SQL generation counts"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated list of account-plugins",
                    content = @Content(schema = @Schema(implementation = AdminAccountPluginPageDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)")
    })
    public ResponseEntity<AdminAccountPluginPageDto> listAccountPlugins(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Admin request: list account-plugins for plugin={}, page={}, size={}", pluginId, page, size);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "activatedAt"));

        Page<AdminAccountPluginDto> accountPlugins = pluginAdminQueryService.listAccountPluginsForPlugin(pluginId, pageable);

        log.info("Returned {} account-plugins for plugin={}", accountPlugins.getNumberOfElements(), pluginId);

        return ResponseEntity.ok(AdminAccountPluginPageDto.fromPage(accountPlugins));
    }

    /**
     * Lists SQL generations for an account-plugin with pagination.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param includeSuperseded whether to include superseded generations
     * @param page page number
     * @param size page size
     * @return paginated list of SQL generations
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/generations")
    @Operation(
            summary = "List SQL generations",
            description = "Returns paginated list of SQL generation records for a specific account-plugin"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated list of SQL generations",
                    content = @Content(schema = @Schema(implementation = SqlGenerationListResponseDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Account-plugin not found")
    })
    public ResponseEntity<SqlGenerationListResponseDto> listGenerations(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Account ID")
            @PathVariable UUID accountId,

            @Parameter(description = "Include superseded generations")
            @RequestParam(defaultValue = "false") boolean includeSuperseded,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Admin request: list generations for plugin={}, account={}, includeSuperseded={}, page={}, size={}",
                pluginId, accountId, includeSuperseded, page, size);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<SqlGenerationSummaryDto> generations = pluginHistoryService.listGenerations(
                pluginId, accountId, includeSuperseded, pageable);

        log.info("Returned {} generations for plugin={}, account={}", generations.getNumberOfElements(), pluginId, accountId);

        return ResponseEntity.ok(SqlGenerationListResponseDto.fromPage(generations));
    }

    /**
     * Gets a single SQL generation by ID.
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/generations/{generationId}")
    @Operation(
            summary = "Get SQL generation details",
            description = "Returns detailed information about a specific SQL generation"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "SQL generation details",
                    content = @Content(schema = @Schema(implementation = SqlGenerationSummaryDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<SqlGenerationSummaryDto> getGeneration(
            @PathVariable String pluginId,
            @PathVariable UUID accountId,
            @PathVariable UUID generationId) {

        log.debug("Admin request: get generation plugin={}, account={}, generation={}",
                pluginId, accountId, generationId);

        SqlGenerationSummaryDto generation = pluginHistoryService.getGeneration(pluginId, accountId, generationId);

        return ResponseEntity.ok(generation);
    }

    /**
     * Gets paginated SQL content for a generation.
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/generations/{generationId}/content")
    @Operation(
            summary = "Get SQL content (paginated)",
            description = "Returns paginated SQL statements for a generation"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated SQL content",
                    content = @Content(schema = @Schema(implementation = SqlContentPageDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<SqlContentPageDto> getSqlContent(
            @PathVariable String pluginId,
            @PathVariable UUID accountId,
            @PathVariable UUID generationId,

            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Statements per page (max 100)")
            @RequestParam(defaultValue = "100") int size) {

        log.debug("Admin request: get SQL content plugin={}, account={}, generation={}, page={}, size={}",
                pluginId, accountId, generationId, page, size);

        int effectiveSize = Math.min(size, 100);
        SqlContentPageDto content = pluginHistoryService.getSqlContent(
                pluginId, accountId, generationId, page, effectiveSize);

        return ResponseEntity.ok(content);
    }

    /**
     * Downloads the complete SQL file.
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/generations/{generationId}/download")
    @Operation(
            summary = "Download SQL file",
            description = "Returns the complete SQL file as a download"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "SQL file download",
                    content = @Content(mediaType = "text/plain")
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<String> downloadSqlFile(
            @PathVariable String pluginId,
            @PathVariable UUID accountId,
            @PathVariable UUID generationId) {

        log.debug("Admin request: download SQL file plugin={}, account={}, generation={}",
                pluginId, accountId, generationId);

        String sqlContent = pluginHistoryService.downloadSqlFile(pluginId, accountId, generationId);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"generation-" + generationId + ".sql\"");
        headers.add(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8");

        return ResponseEntity.ok()
                .headers(headers)
                .body(sqlContent);
    }

    /**
     * Gets summary of what will be deleted when clearing history.
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/history/summary")
    @Operation(
            summary = "Get history clear summary",
            description = "Returns summary of what will be deleted if history is cleared"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "History clear summary",
                    content = @Content(schema = @Schema(implementation = HistoryClearSummaryDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Account-plugin not found")
    })
    public ResponseEntity<HistoryClearSummaryDto> getHistorySummary(
            @PathVariable String pluginId,
            @PathVariable UUID accountId) {

        log.debug("Admin request: get history summary plugin={}, account={}", pluginId, accountId);

        HistoryClearSummaryDto summary = pluginHistoryService.getHistorySummary(pluginId, accountId);

        return ResponseEntity.ok(summary);
    }

    /**
     * Clears all plugin history for an account.
     */
    @DeleteMapping("/{pluginId}/accounts/{accountId}/history")
    @Operation(
            summary = "Clear all plugin history",
            description = "Deletes all SQL generations, S3 files, and deactivates the plugin"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "History cleared successfully",
                    content = @Content(schema = @Schema(implementation = HistoryClearResultDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Confirmation not provided"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Account-plugin not found")
    })
    public ResponseEntity<?> clearHistory(
            @PathVariable String pluginId,
            @PathVariable UUID accountId,

            @Parameter(description = "Must be 'true' to confirm deletion")
            @RequestParam(required = false) Boolean confirm) {

        log.debug("Admin request: clear history plugin={}, account={}, confirm={}", pluginId, accountId, confirm);

        if (confirm == null || !confirm) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "BAD_REQUEST",
                            "message", "Confirmation required. Set confirm=true to proceed with deletion."
                    ));
        }

        HistoryClearResultDto result = pluginHistoryService.clearHistory(pluginId, accountId);

        log.info("History cleared: plugin={}, account={}, deleted={}", pluginId, accountId, result.deletedGenerations());

        return ResponseEntity.ok(result);
    }

    /**
     * Regenerates SQL for a specific generation.
     */
    @PostMapping("/{pluginId}/accounts/{accountId}/generations/{generationId}/regenerate")
    @Operation(
            summary = "Regenerate SQL for a batch",
            description = "Triggers regeneration of SQL for the batch. Original is marked as superseded."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Regeneration completed successfully",
                    content = @Content(schema = @Schema(implementation = RegenerateResultDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Source CSV files not available"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized"),
            @ApiResponse(responseCode = "404", description = "Generation not found"),
            @ApiResponse(responseCode = "409", description = "Generation already superseded")
    })
    public ResponseEntity<?> regenerateSql(
            @PathVariable String pluginId,
            @PathVariable UUID accountId,
            @PathVariable UUID generationId) {

        log.debug("Admin request: regenerate SQL plugin={}, account={}, generation={}",
                pluginId, accountId, generationId);

        try {
            RegenerateResultDto result = pluginHistoryService.regenerateSql(pluginId, accountId, generationId);

            log.info("SQL regenerated: plugin={}, account={}, original={}, new={}",
                    pluginId, accountId, generationId, result.newGenerationId());

            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409)
                    .body(java.util.Map.of(
                            "error", "CONFLICT",
                            "message", e.getMessage()
                    ));
        }
    }

    // ==================== Manual SQL Generation (Admin Tool) ====================

    /**
     * Manually triggers SQL generation for a specific batch.
     *
     * <p>This admin endpoint allows manual SQL generation for batches that may have
     * been missed due to system issues, or to force full regeneration.</p>
     *
     * <p>Use cases:</p>
     * <ul>
     *   <li>Generate SQL for batches that failed due to bugs</li>
     *   <li>Force full SQL generation (all INSERTs) for a batch</li>
     *   <li>Recover from baseline_batch_id issues</li>
     * </ul>
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param request the generation request with batchId and options
     * @return the generation result
     */
    @PostMapping("/{pluginId}/accounts/{accountId}/generate-sql")
    @Operation(
            summary = "Manually generate SQL for a batch",
            description = """
                    Triggers SQL generation for a specific batch. Useful for:
                    - Generating SQL for batches that were missed due to bugs
                    - Forcing full SQL generation (all INSERTs) instead of diff
                    - Recovery operations after system issues

                    **Options:**
                    - `forceFullGeneration=false` (default): Generates diff with previous batch
                    - `forceFullGeneration=true`: Generates all INSERTs (full initialization)

                    **Note:** If the batch is the baseline batch, no SQL will be generated.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "SQL generation completed or skipped",
                    content = @Content(schema = @Schema(implementation = ManualSqlGenerationResultDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Account-plugin or batch not found")
    })
    public ResponseEntity<?> generateSqlForBatch(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Account ID")
            @PathVariable UUID accountId,

            @Valid @RequestBody ManualSqlGenerationRequestDto request) {

        log.info("Admin request: manual SQL generation for plugin={}, account={}, batch={}, forceFullGeneration={}",
                pluginId, accountId, request.batchId(), request.forceFullGeneration());

        // Find the account-plugin
        AccountPlugin accountPlugin = accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No plugin activation found: accountId=" + accountId + ", pluginId=" + pluginId));

        // Validate plugin is active
        if (!accountPlugin.isActive()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "BAD_REQUEST",
                            "message", "Plugin is not active for this account"
                    ));
        }

        try {
            // Generate SQL for the batch
            java.util.Optional<PluginSqlGeneration> generationOpt = sqlGenerationService.generateSqlForBatch(
                    request.batchId(),
                    accountPlugin.getId(),
                    request.forceFullGeneration()
            );

            if (generationOpt.isPresent()) {
                PluginSqlGeneration generation = generationOpt.get();
                log.info("Manual SQL generation completed: batch={}, generation={}, statements={}",
                        request.batchId(), generation.getId(), generation.getStatementCount());

                return ResponseEntity.ok(ManualSqlGenerationResultDto.fromGeneration(
                        generation, request.forceFullGeneration()));
            } else {
                // No SQL generated (baseline batch or already exists)
                log.info("Manual SQL generation skipped for batch={} (baseline or already generated)",
                        request.batchId());

                return ResponseEntity.ok(ManualSqlGenerationResultDto.skipped(
                        request.batchId(),
                        null,
                        "SQL generation skipped - batch is baseline or SQL already exists"
                ));
            }
        } catch (IllegalArgumentException e) {
            log.warn("Manual SQL generation failed: batch={}, error={}", request.batchId(), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "BAD_REQUEST",
                            "message", e.getMessage()
                    ));
        } catch (Exception e) {
            log.error("Manual SQL generation error: batch={}", request.batchId(), e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "error", "INTERNAL_ERROR",
                            "message", "SQL generation failed: " + e.getMessage()
                    ));
        }
    }

    /**
     * Lists batches that don't have SQL generation.
     *
     * <p>Returns all completed batches for an account, indicating which ones
     * have SQL generated and which are baseline batches (that shouldn't have SQL).</p>
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account ID
     * @param page      page number
     * @param size      page size
     * @return list of batches with their SQL status
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/batches-without-sql")
    @Operation(
            summary = "List batches without SQL generation",
            description = """
                    Returns completed batches for an account indicating their SQL generation status.

                    Each batch shows:
                    - `isBaseline=true`: Baseline batch, SQL should NOT be generated
                    - `isBaseline=false`: Non-baseline batch that needs SQL generation

                    Use this to identify batches that need manual SQL generation.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of batches without SQL",
                    content = @Content(schema = @Schema(implementation = BatchWithoutSqlDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Account-plugin not found")
    })
    public ResponseEntity<Page<BatchWithoutSqlDto>> listBatchesWithoutSql(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Account ID")
            @PathVariable UUID accountId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Admin request: list batches without SQL for plugin={}, account={}, page={}, size={}",
                pluginId, accountId, page, size);

        int effectiveSize = Math.min(size, 100);
        // Note: Sort.unsorted() because the native SQL query already has ORDER BY completed_at DESC
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.unsorted());

        try {
            Page<BatchWithoutSqlDto> batches = pluginAdminQueryService.findBatchesWithoutSql(
                    pluginId, accountId, pageable);

            log.info("Found {} batches without SQL for plugin={}, account={}",
                    batches.getTotalElements(), pluginId, accountId);

            return ResponseEntity.ok(batches);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to list batches without SQL: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Lists only batches that actually need SQL generation (non-baseline, no existing SQL).
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account ID
     * @return list of batches needing SQL generation
     */
    @GetMapping("/{pluginId}/accounts/{accountId}/batches-needing-sql")
    @Operation(
            summary = "List batches needing SQL generation",
            description = """
                    Returns only batches that need SQL generation:
                    - Not the baseline batch
                    - Don't have SQL generated yet

                    These are batches that should have SQL but don't, typically due to bugs or system issues.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of batches needing SQL",
                    content = @Content(schema = @Schema(implementation = BatchWithoutSqlDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Account-plugin not found")
    })
    public ResponseEntity<List<BatchWithoutSqlDto>> listBatchesNeedingSql(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Account ID")
            @PathVariable UUID accountId,

            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "100") int size) {

        log.debug("Admin request: list batches needing SQL for plugin={}, account={}", pluginId, accountId);

        int effectiveSize = Math.min(size, 100);
        // Note: Sort.unsorted() because the native SQL query already has ORDER BY completed_at DESC
        Pageable pageable = PageRequest.of(0, effectiveSize, Sort.unsorted());

        try {
            List<BatchWithoutSqlDto> batches = pluginAdminQueryService.findBatchesNeedingSql(
                    pluginId, accountId, pageable);

            log.info("Found {} batches needing SQL for plugin={}, account={}",
                    batches.size(), pluginId, accountId);

            return ResponseEntity.ok(batches);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to list batches needing SQL: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Deletes a single SQL generation and its S3 file.
     *
     * @param pluginId     the plugin identifier
     * @param accountId    the account ID
     * @param generationId the generation ID to delete
     * @param confirm      must be true to confirm deletion
     * @return the deletion result
     */
    @DeleteMapping("/{pluginId}/accounts/{accountId}/generations/{generationId}")
    @Operation(
            summary = "Delete a SQL generation",
            description = """
                    Deletes a single SQL generation record and its associated S3 file.

                    Use this when:
                    - SQL was incorrectly generated
                    - Need to regenerate SQL from scratch for a batch
                    - Cleaning up old generation records

                    **Requires confirmation:** Set confirm=true to proceed with deletion.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Generation deleted successfully",
                    content = @Content(schema = @Schema(implementation = DeleteGenerationResultDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Confirmation not provided"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (requires ROLE_ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<?> deleteGeneration(
            @Parameter(description = "Plugin identifier")
            @PathVariable String pluginId,

            @Parameter(description = "Account ID")
            @PathVariable UUID accountId,

            @Parameter(description = "Generation ID to delete")
            @PathVariable UUID generationId,

            @Parameter(description = "Must be 'true' to confirm deletion")
            @RequestParam(required = false) Boolean confirm) {

        log.debug("Admin request: delete generation plugin={}, account={}, generation={}, confirm={}",
                pluginId, accountId, generationId, confirm);

        if (confirm == null || !confirm) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "BAD_REQUEST",
                            "message", "Confirmation required. Set confirm=true to proceed with deletion."
                    ));
        }

        try {
            DeleteGenerationResultDto result = pluginHistoryService.deleteGeneration(
                    pluginId, accountId, generationId);

            log.info("Generation deleted: plugin={}, account={}, generation={}",
                    pluginId, accountId, generationId);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete generation: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
