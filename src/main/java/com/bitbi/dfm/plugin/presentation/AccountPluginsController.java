package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.application.PluginAdminQueryService;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.application.PluginQueryService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import com.bitbi.dfm.plugin.presentation.dto.*;
import jakarta.validation.Valid;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST controller for viewing account plugin integrations.
 *
 * <p>Handles user-facing plugin queries for authenticated accounts.
 * Requires OAuth2 authentication with accountId claim.</p>
 *
 * <p>Phase 6 (User Story 4) implementation:</p>
 * <ul>
 *   <li>GET /api/v1/account/plugins - List plugin integrations (FR-011, FR-012)</li>
 * </ul>
 *
 * @see PluginQueryService
 * @see AccountPluginListResponseDto
 */
@RestController
@RequestMapping(ApiRoutes.ACCOUNT_PLUGINS)
@Validated
@Tag(name = "Account Plugins", description = "View and manage account plugin integrations")
@SecurityRequirement(name = "oauth2")
public class AccountPluginsController {

    private static final Logger log = LoggerFactory.getLogger(AccountPluginsController.class);

    private final PluginQueryService pluginQueryService;
    private final PluginAdminQueryService pluginAdminQueryService;
    private final PluginAuditLogRepository auditLogRepository;
    private final PluginHistoryService pluginHistoryService;
    private final PluginRateLimiterService rateLimiterService;
    private final SqlGenerationService sqlGenerationService;
    private final AccountPluginRepository accountPluginRepository;
    private final AuthorizationHelper authorizationHelper;
    private final SiteRepository siteRepository;
    private final ParquetExportCredentialsService parquetExportCredentialsService;
    private final PluginApiKeyService pluginApiKeyService;

    public AccountPluginsController(
            PluginQueryService pluginQueryService,
            PluginAdminQueryService pluginAdminQueryService,
            PluginAuditLogRepository auditLogRepository,
            PluginHistoryService pluginHistoryService,
            PluginRateLimiterService rateLimiterService,
            SqlGenerationService sqlGenerationService,
            AccountPluginRepository accountPluginRepository,
            AuthorizationHelper authorizationHelper,
            SiteRepository siteRepository,
            ParquetExportCredentialsService parquetExportCredentialsService,
            PluginApiKeyService pluginApiKeyService) {
        this.pluginQueryService = pluginQueryService;
        this.pluginAdminQueryService = pluginAdminQueryService;
        this.auditLogRepository = auditLogRepository;
        this.pluginHistoryService = pluginHistoryService;
        this.rateLimiterService = rateLimiterService;
        this.sqlGenerationService = sqlGenerationService;
        this.accountPluginRepository = accountPluginRepository;
        this.authorizationHelper = authorizationHelper;
        this.siteRepository = siteRepository;
        this.parquetExportCredentialsService = parquetExportCredentialsService;
        this.pluginApiKeyService = pluginApiKeyService;
    }

    /**
     * Lists active plugin integrations for the authenticated account.
     *
     * <p>Implements FR-011 (list active plugins) and FR-012 (exclude sensitive plugin data).</p>
     *
     * <p>Security: Plugin-specific data (tenantId, etc.) is NOT exposed.
     * Only metadata (name, activation date, last used) is returned.</p>
     *
     * @param page page number (0-indexed)
     * @param size page size (1-100)
     * @param includeInactive whether to include deactivated plugins
     * @return paginated list of plugin summaries
     */
    @GetMapping
    @Operation(
        summary = "List account plugin integrations",
        description = """
            Returns a paginated list of plugin integrations for the authenticated account.

            **Security (FR-012):**
            - Plugin-specific data (tenantId, etc.) is NOT exposed
            - Only metadata (plugin name, activation date, last used) is returned

            **Filtering:**
            - By default, only active plugins are returned
            - Use `includeInactive=true` to include deactivated plugins
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "List of plugin integrations",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountPluginListResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        )
    })
    public ResponseEntity<AccountPluginListResponseDto> listAccountPlugins(
            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page must be 0 or greater")
            int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Size must be at least 1")
            @Max(value = 100, message = "Size must not exceed 100")
            int size,

            @Parameter(description = "Include deactivated plugins in results")
            @RequestParam(defaultValue = "false")
            boolean includeInactive) {

        // Use Optional to gracefully handle admin users who don't have Account records
        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();

        if (accountIdOpt.isEmpty()) {
            // Admin user without Account - return empty list
            log.info("Admin user without Account record - returning empty plugin list");
            return ResponseEntity.ok(AccountPluginListResponseDto.of(
                    List.of(), page, size, 0L, 0));
        }

        UUID accountId = accountIdOpt.get();
        log.info("Listing plugins for account {}, includeInactive={}, page={}, size={}",
                accountId, includeInactive, page, size);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "activatedAt"));

        AccountPluginListResponseDto response = pluginQueryService.listAccountPlugins(
                accountId, includeInactive, pageable);

        log.debug("Returning {} plugins for account {}", response.content().size(), accountId);

        return ResponseEntity.ok(response);
    }

    /**
     * Lists audit log entries for a specific plugin.
     *
     * <p>Returns events like activations, SQL generation, and errors.
     * Only returns logs for the authenticated account.</p>
     *
     * @param pluginId the plugin identifier (e.g., "bit-bi")
     * @param page page number (0-indexed)
     * @param size page size (1-100)
     * @param siteId optional filter by site ID
     * @param from optional filter by start date (inclusive)
     * @param to optional filter by end date (exclusive)
     * @return paginated list of log entries
     */
    @GetMapping("/{pluginId}/logs")
    @Operation(
        summary = "List plugin logs for authenticated account",
        description = """
            Returns audit log entries for the specified plugin and authenticated account.

            **Events logged:**
            - Plugin activation/deactivation
            - SQL generation started/completed/failed
            - Event dispatch success/failure

            **Filtering:**
            - siteId: Filter by site (for SQL-related events)
            - from/to: Filter by date range (ISO 8601 format)

            **Security:**
            - Only returns logs for the authenticated account
            - Sensitive data (IP, user agent) is excluded
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "List of plugin log entries",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserPluginLogPageResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        )
    })
    public ResponseEntity<UserPluginLogPageResponseDto> getPluginLogs(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable
            @Size(min = 1, max = 64, message = "Plugin ID must be 1-64 characters")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Plugin ID must be lowercase alphanumeric with hyphens")
            String pluginId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page must be 0 or greater")
            int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Size must be at least 1")
            @Max(value = 100, message = "Size must not exceed 100")
            int size,

            @Parameter(description = "Filter by site ID")
            @RequestParam(required = false)
            UUID siteId,

            @Parameter(description = "Filter by start date (inclusive, ISO 8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,

            @Parameter(description = "Filter by end date (exclusive, ISO 8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to) {

        // Get authenticated account ID
        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();

        if (accountIdOpt.isEmpty()) {
            // Admin user without Account - return empty list
            log.info("Admin user without Account record - returning empty log list");
            return ResponseEntity.ok(new UserPluginLogPageResponseDto(
                    List.of(), page, size, 0L, 0));
        }

        UUID accountId = accountIdOpt.get();
        log.info("Listing logs for plugin {} account {}, page={}, size={}, siteId={}, from={}, to={}",
                pluginId, accountId, page, size, siteId, from, to);

        Pageable pageable = PageRequest.of(page, size);

        // Use filtered query if any filter is present, otherwise use simple query
        Page<PluginAuditLog> logsPage;
        if (siteId != null || from != null || to != null) {
            logsPage = auditLogRepository.findByPluginIdAndAccountIdWithFilters(
                    pluginId, accountId, siteId, from, to, pageable);
        } else {
            logsPage = auditLogRepository.findByPluginIdAndAccountId(pluginId, accountId, pageable);
        }

        // Collect all unique siteIds from the logs for bulk lookup
        Set<UUID> siteIds = logsPage.getContent().stream()
                .map(log -> extractSiteIdFromMetadata(log.getMetadata()))
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // Bulk fetch all sites
        Map<UUID, Site> sitesById = siteRepository.findAllById(siteIds).stream()
                .collect(Collectors.toMap(Site::getId, Function.identity()));

        // Map logs to DTOs with site domain information
        List<UserPluginLogDto> dtos = logsPage.getContent().stream()
                .map(auditLog -> {
                    UUID logSiteId = extractSiteIdFromMetadata(auditLog.getMetadata());
                    String siteDomain = null;
                    if (logSiteId != null && sitesById.containsKey(logSiteId)) {
                        siteDomain = sitesById.get(logSiteId).getSiteName();
                    }
                    return UserPluginLogDto.fromEntityWithSite(auditLog, siteDomain);
                })
                .toList();

        Page<UserPluginLogDto> dtoPage = new PageImpl<>(dtos, pageable, logsPage.getTotalElements());

        log.debug("Returning {} logs for plugin {} account {}", dtoPage.getContent().size(), pluginId, accountId);

        return ResponseEntity.ok(UserPluginLogPageResponseDto.fromPage(dtoPage));
    }

    /**
     * Extracts siteId from audit log metadata.
     */
    private UUID extractSiteIdFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object siteIdObj = metadata.get("siteId");
        if (siteIdObj == null) {
            return null;
        }
        if (siteIdObj instanceof UUID) {
            return (UUID) siteIdObj;
        }
        try {
            return UUID.fromString(siteIdObj.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reinitializes plugin by clearing all SQL history and setting new baseline batch.
     *
     * <p>Feature 015: Plugin Reinit Option - User Story 2</p>
     *
     * <p>What happens:
     * <ol>
     *   <li>All existing SQL generation records are deleted</li>
     *   <li>All S3 SQL files for this plugin are deleted</li>
     *   <li>Latest completed batch is set as new baseline</li>
     *   <li>API key and plugin configuration remain unchanged</li>
     * </ol>
     *
     * <p><strong>Important:</strong> SQL is NOT generated during reinit.
     * Client must download CSV files via /sites/{siteId}/files endpoint
     * for the baseline data. SQL generation resumes for future batches.</p>
     *
     * @param pluginId the plugin identifier (e.g., "bit-bi")
     * @return the reinit result
     */
    @PostMapping("/{pluginId}/reinit")
    @Operation(
        summary = "Reinitialize plugin by resetting baseline",
        description = """
            Clears all existing SQL generation history for the plugin and sets
            the latest completed batch as the new baseline.

            **What happens:**
            1. All existing SQL generation records are deleted
            2. All S3 SQL files for this plugin are deleted
            3. Latest completed batch is set as new baseline
            4. API key and plugin configuration remain unchanged

            **Important:** SQL is NOT generated during reinit.
            Client must download CSV files via /sites/{siteId}/files endpoint
            for the baseline data. SQL generation resumes for future batches.

            **Use cases:**
            - Data has drifted and you need a fresh baseline
            - Want to rebuild SQL history without changing API key
            - Troubleshooting SQL generation issues
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description = "Reinit completed - baseline reset, client should download CSV files",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ReinitResultDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad request (plugin not active)"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Plugin not found or not activated for this account"
        ),
        @ApiResponse(
            responseCode = "429",
            description = "Too many requests - rate limited (1 request per 30 seconds)"
        )
    })
    public ResponseEntity<ReinitResultDto> reinitPlugin(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable
            @Size(min = 1, max = 64, message = "Plugin ID must be 1-64 characters")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Plugin ID must be lowercase alphanumeric with hyphens")
            String pluginId) {

        // Get authenticated account ID
        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();

        if (accountIdOpt.isEmpty()) {
            // Admin user without Account - cannot reinit
            log.warn("Admin user without Account record attempted reinit for plugin {}", pluginId);
            throw new IllegalArgumentException("Account not found for authenticated user");
        }

        UUID accountId = accountIdOpt.get();

        // Check rate limiting (1 request per 30 seconds)
        if (!rateLimiterService.tryConsumeReinit(accountId)) {
            log.warn("Reinit rate limit exceeded for plugin {} account {}", pluginId, accountId);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rateLimiterService.getReinitRetryAfterSeconds()))
                    .build();
        }

        log.info("Reinit requested for plugin {} account {}", pluginId, accountId);

        ReinitResultDto result = pluginHistoryService.reinit(pluginId, accountId);

        log.info("Reinit initiated for plugin {} account {}: deleted={}, sqlGenerationTriggered={}",
                pluginId, accountId, result.deletedGenerations(), result.sqlGenerationTriggered());

        // Return 202 Accepted - SQL generation continues asynchronously in background
        // Client can poll /sql-changes to check when generation completes
        return ResponseEntity.accepted().body(result);
    }

    /**
     * Rotates the Parquet Export plugin's Basic Auth password (028).
     *
     * <p>The login stays stable; the old password stops authenticating immediately. The new raw
     * password appears in this response exactly once.</p>
     *
     * @return login + new raw password
     */
    @PostMapping("/parquet-export/rotate-password")
    @Operation(
        summary = "Rotate Parquet Export Basic Auth password",
        description = """
            Generates a new password for the Parquet Export plugin's Basic Auth credentials.
            The login stays the same; the old password is invalidated immediately.

            **Important:** The new password is shown only in this response and cannot be
            retrieved later — store it securely.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "New credentials issued",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = RotatePasswordResponseDto.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "403", description = "Plugin not activated for this account")
    })
    public ResponseEntity<RotatePasswordResponseDto> rotateParquetExportPassword() {
        UUID accountId = authorizationHelper.getOptionalAuthenticatedAccountId()
                .orElseThrow(() -> new IllegalArgumentException("Account not found for authenticated user"));

        ParquetExportCredentials credentials = parquetExportCredentialsService.rotatePassword(accountId);
        return ResponseEntity.ok(RotatePasswordResponseDto.fromCredentials(credentials));
    }

    /**
     * Rotates the Bit BI plugin's API key (#66).
     *
     * <p>The old key stops authenticating immediately. The new raw key appears in this response
     * exactly once. Rotating also re-derives the indexed SHA-256 lookup handle, which is how an
     * activation issued before V42 leaves the legacy validation path.</p>
     *
     * @return the new raw API key
     */
    @PostMapping("/bit-bi/rotate-api-key")
    @Operation(
        summary = "Rotate the Bit BI plugin API key",
        description = """
            Generates a new API key for the Bit BI plugin. The previous key is invalidated
            immediately, so any client using it must be updated.

            **Important:** The new key is shown only in this response and cannot be
            retrieved later — store it securely.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "New API key issued",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = RotateApiKeyResponseDto.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "403", description = "Plugin not activated for this account")
    })
    public ResponseEntity<RotateApiKeyResponseDto> rotateBitBiApiKey() {
        UUID accountId = authorizationHelper.getOptionalAuthenticatedAccountId()
                .orElseThrow(() -> new IllegalArgumentException("Account not found for authenticated user"));

        PluginApiKey apiKey = pluginApiKeyService.rotateApiKey(accountId);
        return ResponseEntity.ok(RotateApiKeyResponseDto.fromApiKey(apiKey));
    }

    // ==================== Batch SQL Management (User-facing) ====================

    /**
     * Lists all completed batches with their SQL generation status.
     *
     * @param pluginId the plugin identifier
     * @param page page number
     * @param size page size
     * @param siteId optional filter by site ID
     * @return paginated list of batches with SQL status
     */
    @GetMapping("/{pluginId}/batches")
    @Operation(
        summary = "List batches with SQL status",
        description = """
            Returns all completed batches for your account with their SQL generation status.

            Each batch shows:
            - `isBaseline=true`: Baseline batch - SQL is NOT generated
            - `hasSql=true`: SQL has been generated
            - `hasSql=false`: SQL has NOT been generated yet

            **Filtering:**
            - siteId: Filter by site

            Use this to identify which batches need SQL generation.
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "List of batches with SQL status",
            content = @Content(schema = @Schema(implementation = BatchWithSqlStatusDto.class))
        ),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Plugin not activated for this account")
    })
    public ResponseEntity<Page<BatchWithSqlStatusDto>> listBatches(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable String pluginId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "Filter by site ID")
            @RequestParam(required = false) UUID siteId) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();
        log.debug("Listing batches for plugin {} account {} siteId={}", pluginId, accountId, siteId);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize);

        try {
            Page<BatchWithSqlStatusDto> batches = pluginAdminQueryService.findBatchesWithSqlStatus(
                    pluginId, accountId, siteId, pageable);
            return ResponseEntity.ok(batches);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to list batches: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Generates SQL for a specific batch.
     *
     * @param pluginId the plugin identifier
     * @param request the generation request
     * @return the generation result
     */
    @PostMapping("/{pluginId}/generate-sql")
    @Operation(
        summary = "Generate SQL for a batch",
        description = """
            Triggers SQL generation for a specific batch.

            **Options:**
            - `forceFullGeneration=false` (default): Generates diff with previous batch
            - `forceFullGeneration=true`: Generates all INSERTs (full initialization)

            **Note:** SQL cannot be generated for the baseline batch.
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "SQL generation completed or skipped",
            content = @Content(schema = @Schema(implementation = ManualSqlGenerationResultDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid request or batch not found"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Plugin not activated for this account")
    })
    public ResponseEntity<?> generateSql(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable String pluginId,

            @Valid @RequestBody ManualSqlGenerationRequestDto request) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();
        log.info("Generate SQL requested: plugin={}, account={}, batch={}, forceFullGeneration={}",
                pluginId, accountId, request.batchId(), request.forceFullGeneration());

        // Find the account-plugin
        AccountPlugin accountPlugin = accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId)
                .orElse(null);
        if (accountPlugin == null) {
            return ResponseEntity.notFound().build();
        }

        if (!accountPlugin.isActive()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "BAD_REQUEST", "message", "Plugin is not active"));
        }

        try {
            java.util.Optional<PluginSqlGeneration> generationOpt = sqlGenerationService.generateSqlForBatch(
                    request.batchId(), accountPlugin.getId(), request.forceFullGeneration());

            if (generationOpt.isPresent()) {
                PluginSqlGeneration generation = generationOpt.get();
                log.info("SQL generation completed: batch={}, generation={}", request.batchId(), generation.getId());
                return ResponseEntity.ok(ManualSqlGenerationResultDto.fromGeneration(generation, request.forceFullGeneration()));
            } else {
                log.info("SQL generation skipped for batch={}", request.batchId());
                return ResponseEntity.ok(ManualSqlGenerationResultDto.skipped(
                        request.batchId(), null, "SQL generation skipped - batch is baseline or SQL already exists"));
            }
        } catch (IllegalArgumentException e) {
            log.warn("SQL generation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("SQL generation error: batch={}", request.batchId(), e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "INTERNAL_ERROR", "message", "SQL generation failed: " + e.getMessage()));
        }
    }

    /**
     * Lists SQL generations for this account.
     */
    @GetMapping("/{pluginId}/generations")
    @Operation(
        summary = "List SQL generations",
        description = "Returns paginated list of SQL generation records for your account"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of SQL generations"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Plugin not activated")
    })
    public ResponseEntity<SqlGenerationListResponseDto> listGenerations(
            @PathVariable String pluginId,

            @RequestParam(defaultValue = "false") boolean includeSuperseded,

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "20") int size) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();
        log.debug("Listing generations for plugin {} account {}", pluginId, accountId);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        try {
            Page<SqlGenerationSummaryDto> generations = pluginHistoryService.listGenerations(
                    pluginId, accountId, includeSuperseded, pageable);
            return ResponseEntity.ok(SqlGenerationListResponseDto.fromPage(generations));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Gets paginated SQL content for a generation.
     */
    @GetMapping("/{pluginId}/generations/{generationId}/content")
    @Operation(
        summary = "Get SQL content (paginated)",
        description = "Returns paginated SQL statements from a generation"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated SQL content"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<SqlContentPageDto> getSqlContent(
            @PathVariable String pluginId,
            @PathVariable UUID generationId,

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "100") int size) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();
        log.debug("Getting SQL content for generation {} account {}", generationId, accountId);

        int effectiveSize = Math.min(size, 100);

        try {
            SqlContentPageDto content = pluginHistoryService.getSqlContent(
                    pluginId, accountId, generationId, page, effectiveSize);
            return ResponseEntity.ok(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Downloads the complete SQL file.
     */
    @GetMapping("/{pluginId}/generations/{generationId}/download")
    @Operation(
        summary = "Download SQL file",
        description = "Returns the complete SQL file as a download"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SQL file download"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<String> downloadSqlFile(
            @PathVariable String pluginId,
            @PathVariable UUID generationId) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();
        log.debug("Downloading SQL file for generation {} account {}", generationId, accountId);

        try {
            String sqlContent = pluginHistoryService.downloadSqlFile(pluginId, accountId, generationId);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.add(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"generation-" + generationId + ".sql\"");
            headers.add(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8");

            return ResponseEntity.ok().headers(headers).body(sqlContent);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Deletes a SQL generation.
     */
    @DeleteMapping("/{pluginId}/generations/{generationId}")
    @Operation(
        summary = "Delete SQL generation",
        description = "Deletes a SQL generation and its S3 file. Requires confirmation."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Generation deleted"),
        @ApiResponse(responseCode = "400", description = "Confirmation required"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Generation not found")
    })
    public ResponseEntity<?> deleteGeneration(
            @PathVariable String pluginId,
            @PathVariable UUID generationId,

            @Parameter(description = "Must be 'true' to confirm deletion")
            @RequestParam(required = false) Boolean confirm) {

        Optional<UUID> accountIdOpt = authorizationHelper.getOptionalAuthenticatedAccountId();
        if (accountIdOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UUID accountId = accountIdOpt.get();

        if (confirm == null || !confirm) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "BAD_REQUEST",
                            "message", "Confirmation required. Set confirm=true to proceed."));
        }

        log.info("Delete generation requested: plugin={}, account={}, generation={}", pluginId, accountId, generationId);

        try {
            DeleteGenerationResultDto result = pluginHistoryService.deleteGeneration(pluginId, accountId, generationId);
            log.info("Generation deleted: {}", generationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
