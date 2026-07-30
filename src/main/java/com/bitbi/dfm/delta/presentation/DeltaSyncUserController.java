package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.delta.application.DeltaSegmentParquetQueryService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointDownloadResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSyncStateResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner-facing REST controller for a site's Delta v2 sync surfaces (feature 023 — Delta Sync UI).
 * <p>
 * API Path: /api/v1/account/sites/{siteId}/delta<br>
 * Authentication: Auth0 OAuth2 JWT with accountId claim; the site must belong to the
 * authenticated account.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping(ApiRoutes.DELTA_SYNC_USER)
@Tag(name = "UI/Admin API - Delta Sync (Owner)", description = "Sync state and checkpoints of the account's own Delta v2 sites")
@SecurityRequirement(name = "oauth2")
public class DeltaSyncUserController {

    private static final Logger logger = LoggerFactory.getLogger(DeltaSyncUserController.class);

    private final DeltaSyncStateService syncStateService;
    private final DeltaCheckpointQueryService checkpointQueryService;
    private final DeltaSegmentParquetQueryService segmentParquetQueryService;
    private final SiteService siteService;
    private final AuthorizationHelper authorizationHelper;

    public DeltaSyncUserController(DeltaSyncStateService syncStateService,
                                   DeltaCheckpointQueryService checkpointQueryService,
                                   DeltaSegmentParquetQueryService segmentParquetQueryService,
                                   SiteService siteService,
                                   AuthorizationHelper authorizationHelper) {
        this.syncStateService = syncStateService;
        this.checkpointQueryService = checkpointQueryService;
        this.segmentParquetQueryService = segmentParquetQueryService;
        this.siteService = siteService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Get the current delta sync state of an owned site.
     * <p>
     * GET /api/v1/account/sites/{siteId}/delta/sync-state
     * </p>
     *
     * @param siteId site identifier
     * @return sync state projection, or 404 when the client has never connected
     */
    @GetMapping("/sync-state")
    @Operation(
            summary = "Get delta sync state",
            description = "Returns the site's Delta v2 sync watermark, checkpoint pointer, schema version and pending "
                    + "rebaseline/rebuild flags. 404 when the Delta client has never connected (no sync state row)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sync state retrieved",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeltaSyncStateResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found, or no sync activity yet",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeltaSyncStateResponseDto> getSyncState(@PathVariable UUID siteId) {
        requireOwnedSite(siteId);
        return syncStateService.findSyncState(siteId)
                .map(state -> ResponseEntity.ok(DeltaSyncStateResponseDto.fromEntity(state)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List the per-table checkpoints of an owned site.
     * <p>
     * GET /api/v1/account/sites/{siteId}/delta/checkpoints
     * </p>
     *
     * @param siteId site identifier
     * @return checkpoints sorted by table name (empty array when none)
     */
    @GetMapping("/checkpoints")
    @Operation(
            summary = "List delta checkpoints",
            description = "Returns the site's per-table checkpoint rows (seq, row count, last update, file-presence "
                    + "flags), sorted by table name. Download URLs are issued by the separate download endpoint."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Checkpoints retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<DeltaCheckpointResponseDto>> listCheckpoints(@PathVariable UUID siteId) {
        requireOwnedSite(siteId);
        List<DeltaCheckpointResponseDto> response = checkpointQueryService.listCheckpoints(siteId).stream()
                .map(DeltaCheckpointResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Issue a fresh presigned download URL for one checkpoint file of an owned site.
     * <p>
     * GET /api/v1/account/sites/{siteId}/delta/checkpoints/{tableName}/download?format=csv|parquet
     * </p>
     *
     * @param siteId    site identifier
     * @param tableName checkpoint table name
     * @param format    file format: {@code csv} or {@code parquet}
     * @return presigned download URL (15-minute expiry)
     */
    @GetMapping("/checkpoints/{tableName}/download")
    @Operation(
            summary = "Presign a checkpoint download",
            description = "Issues a fresh presigned S3 URL (15-minute expiry) for one checkpoint file. Called per "
                    + "click — the URL is never cached or embedded in the checkpoint list."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Presigned URL issued",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeltaCheckpointDownloadResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Unsupported format",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site, checkpoint or requested file not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeltaCheckpointDownloadResponseDto> downloadCheckpoint(@PathVariable UUID siteId,
                                                                                 @PathVariable String tableName,
                                                                                 @RequestParam String format) {
        requireOwnedSite(siteId);
        return checkpointQueryService.presignDownload(siteId, tableName, format)
                .map(download -> ResponseEntity.ok(DeltaCheckpointDownloadResponseDto.of(download)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Request a full re-baseline of an owned site.
     * <p>
     * POST /api/v1/account/sites/{siteId}/delta/rebaseline
     * </p>
     *
     * @param siteId site identifier
     * @return 202 Accepted once the flag is raised
     */
    @PostMapping("/rebaseline")
    @Operation(
            summary = "Request full re-baseline",
            description = "Raises the persistent rebaseline_requested flag: on its next connect the Delta client is "
                    + "answered NEED_REBASELINE and re-sends a full snapshot. The flag is cleared when that "
                    + "FULL_SNAPSHOT session commits, so a snapshot that drops part-way re-arms a clean retry."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Re-baseline requested",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Map<String, String>> requestRebaseline(@PathVariable UUID siteId) {
        requireOwnedSite(siteId);
        syncStateService.requestRebaseline(siteId);
        logger.info("Full re-baseline requested by owner: siteId={}", siteId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "requested"));
    }


    /**
     * Issue a fresh presigned download URL for one table's delta Parquet file of a batch
     * (feature 025).
     * <p>
     * GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{tableName}/parquet
     * </p>
     *
     * @param siteId    site identifier
     * @param batchId   batch (= Delta session) identifier
     * @param tableName table whose delta file to download
     * @return presigned download URL (15-minute expiry)
     */
    @GetMapping("/batches/{batchId}/tables/{tableName}/parquet")
    @Operation(
            summary = "Presign a batch delta Parquet download",
            description = "Issues a fresh presigned S3 URL (15-minute expiry) for the typed delta Parquet file that "
                    + "the egress worker materialized for one table of the batch's changelog segment. 404 when the "
                    + "batch has no segment or the table's file was never egressed (no declared schema)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Presigned URL issued",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeltaCheckpointDownloadResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site, segment or delta Parquet file not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeltaCheckpointDownloadResponseDto> downloadBatchParquet(@PathVariable UUID siteId,
                                                                                   @PathVariable UUID batchId,
                                                                                   @PathVariable String tableName) {
        requireOwnedSite(siteId);
        return segmentParquetQueryService.presignBatchTableParquet(siteId, batchId, tableName)
                .map(download -> ResponseEntity.ok(DeltaCheckpointDownloadResponseDto.of(download)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Load the site and assert it belongs to the authenticated account.
     *
     * @param siteId site identifier
     * @return the owned site
     * @throws AccessDeniedException when the site belongs to another account
     */
    private Site requireOwnedSite(UUID siteId) {
        UUID accountId = authorizationHelper.getAuthenticatedAccountId();
        Site site = siteService.getSite(siteId);
        if (!site.getAccountId().equals(accountId)) {
            logger.warn("Delta sync access denied: siteId={}, authenticatedAccountId={}, siteAccountId={}",
                    siteId, accountId, site.getAccountId());
            throw new AccessDeniedException("Site does not belong to the authenticated account");
        }
        return site;
    }
}
