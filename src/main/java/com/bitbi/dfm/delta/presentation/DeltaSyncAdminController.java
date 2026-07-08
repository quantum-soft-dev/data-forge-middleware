package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.delta.application.DeltaCheckpointRebuildService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointDownloadResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSegmentResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSyncStateResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
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
 * Admin-facing REST controller for a site's Delta v2 sync surfaces (feature 023 — Delta Sync UI).
 * <p>
 * API Path: /api/v1/sites/{siteId}/delta<br>
 * Authentication: Auth0 OAuth2 (ROLE_ADMIN — enforced by the security filter chain for
 * {@code /api/v1/sites/**}).
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping(ApiRoutes.DELTA_SYNC_ADMIN)
@Tag(name = "UI/Admin API - Delta Sync (Admin)", description = "Sync state, checkpoints and operational actions for any Delta v2 site")
@SecurityRequirement(name = "oauth2")
public class DeltaSyncAdminController {

    private static final int DEFAULT_SEGMENTS_LIMIT = 20;
    private static final int MAX_SEGMENTS_LIMIT = 100;

    private final DeltaSyncStateService syncStateService;
    private final DeltaCheckpointQueryService checkpointQueryService;
    private final DeltaCheckpointRebuildService checkpointRebuildService;
    private final ChangelogSegmentService changelogSegmentService;
    private final SiteService siteService;

    public DeltaSyncAdminController(DeltaSyncStateService syncStateService,
                                    DeltaCheckpointQueryService checkpointQueryService,
                                    DeltaCheckpointRebuildService checkpointRebuildService,
                                    ChangelogSegmentService changelogSegmentService,
                                    SiteService siteService) {
        this.syncStateService = syncStateService;
        this.checkpointQueryService = checkpointQueryService;
        this.checkpointRebuildService = checkpointRebuildService;
        this.changelogSegmentService = changelogSegmentService;
        this.siteService = siteService;
    }

    /**
     * Get the current delta sync state of any site.
     * <p>
     * GET /api/v1/sites/{siteId}/delta/sync-state
     * </p>
     *
     * @param siteId site identifier
     * @return sync state projection, or 404 when the client has never connected
     */
    @GetMapping("/sync-state")
    @Operation(
            summary = "Get delta sync state (admin)",
            description = "Returns the site's Delta v2 sync watermark, checkpoint pointer, schema version and pending "
                    + "rebaseline/rebuild flags. 404 when the Delta client has never connected (no sync state row)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sync state retrieved",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeltaSyncStateResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found, or no sync activity yet",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeltaSyncStateResponseDto> getSyncState(@PathVariable UUID siteId) {
        siteService.getSite(siteId); // 404 when the site does not exist
        return syncStateService.findSyncState(siteId)
                .map(state -> ResponseEntity.ok(DeltaSyncStateResponseDto.fromEntity(state)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List the per-table checkpoints of any site.
     * <p>
     * GET /api/v1/sites/{siteId}/delta/checkpoints
     * </p>
     *
     * @param siteId site identifier
     * @return checkpoints sorted by table name (empty array when none)
     */
    @GetMapping("/checkpoints")
    @Operation(
            summary = "List delta checkpoints (admin)",
            description = "Returns the site's per-table checkpoint rows (seq, row count, last update, file-presence "
                    + "flags), sorted by table name. Download URLs are issued by the separate download endpoint."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Checkpoints retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<DeltaCheckpointResponseDto>> listCheckpoints(@PathVariable UUID siteId) {
        siteService.getSite(siteId); // 404 when the site does not exist
        List<DeltaCheckpointResponseDto> response = checkpointQueryService.listCheckpoints(siteId).stream()
                .map(DeltaCheckpointResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Issue a fresh presigned download URL for one checkpoint file of any site.
     * <p>
     * GET /api/v1/sites/{siteId}/delta/checkpoints/{tableName}/download?format=csv|parquet
     * </p>
     *
     * @param siteId    site identifier
     * @param tableName checkpoint table name
     * @param format    file format: {@code csv} or {@code parquet}
     * @return presigned download URL (15-minute expiry)
     */
    @GetMapping("/checkpoints/{tableName}/download")
    @Operation(
            summary = "Presign a checkpoint download (admin)",
            description = "Issues a fresh presigned S3 URL (15-minute expiry) for one checkpoint file. Called per "
                    + "click — the URL is never cached or embedded in the checkpoint list."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Presigned URL issued",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeltaCheckpointDownloadResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Unsupported format",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site, checkpoint or requested file not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeltaCheckpointDownloadResponseDto> downloadCheckpoint(@PathVariable UUID siteId,
                                                                                 @PathVariable String tableName,
                                                                                 @RequestParam String format) {
        siteService.getSite(siteId); // 404 when the site does not exist
        return checkpointQueryService.presignDownload(siteId, tableName, format)
                .map(download -> ResponseEntity.ok(DeltaCheckpointDownloadResponseDto.of(download)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    /**
     * Queue a forced out-of-schedule checkpoint rebuild.
     * <p>
     * POST /api/v1/sites/{siteId}/delta/checkpoints/rebuild
     * </p>
     *
     * @param siteId site identifier
     * @return 202 Accepted once the rebuild is flagged and scheduled
     */
    @PostMapping("/checkpoints/rebuild")
    @Operation(
            summary = "Rebuild checkpoint now (admin)",
            description = "Sets the persistent rebuild_requested flag (shown as 'Rebuild queued' in the UI) and runs "
                    + "the checkpoint build asynchronously outside the regular schedule; the flag is cleared when "
                    + "the rebuild completes."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Checkpoint rebuild scheduled",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Map<String, String>> rebuildCheckpoint(@PathVariable UUID siteId) {
        siteService.getSite(siteId); // 404 when the site does not exist
        checkpointRebuildService.requestRebuild(siteId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "scheduled"));
    }

    /**
     * Request a full re-baseline of any site.
     * <p>
     * POST /api/v1/sites/{siteId}/delta/rebaseline
     * </p>
     *
     * @param siteId site identifier
     * @return 202 Accepted once the flag is raised
     */
    @PostMapping("/rebaseline")
    @Operation(
            summary = "Request full re-baseline (admin)",
            description = "Raises the persistent rebaseline_requested flag: on its next connect the Delta client is "
                    + "answered NEED_REBASELINE and re-sends a full snapshot. The flag is cleared when the "
                    + "FULL_SNAPSHOT session actually starts."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Re-baseline requested",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Map<String, String>> requestRebaseline(@PathVariable UUID siteId) {
        siteService.getSite(siteId); // 404 when the site does not exist
        syncStateService.requestRebaseline(siteId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "requested"));
    }

    /**
     * List the most recent changelog segments of any site, newest first.
     * <p>
     * GET /api/v1/sites/{siteId}/delta/segments?limit=20
     * </p>
     * <p>Admin only — owner lite projection deferred until product decision P2
     * (specs/022-delta-client-v2/ui-redesign-tasks.md).</p>
     *
     * @param siteId site identifier
     * @param limit  maximum segments to return (default 20, max 100)
     * @return segments ordered by createdAt desc (empty array when none)
     */
    @GetMapping("/segments")
    @Operation(
            summary = "List recent changelog segments (admin)",
            description = "Returns the site's most recent changelog segments (seq range, record count, session mode, "
                    + "created time), newest first. Storage keys are never exposed."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Segments retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<DeltaSegmentResponseDto>> listSegments(
            @PathVariable UUID siteId,
            @RequestParam(defaultValue = "" + DEFAULT_SEGMENTS_LIMIT) int limit) {
        siteService.getSite(siteId); // 404 when the site does not exist
        int effectiveLimit = Math.clamp(limit, 1, MAX_SEGMENTS_LIMIT);
        List<DeltaSegmentResponseDto> response = changelogSegmentService.listRecentSegments(siteId, effectiveLimit).stream()
                .map(DeltaSegmentResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }
}
