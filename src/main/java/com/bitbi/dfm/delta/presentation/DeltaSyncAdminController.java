package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.BatchParquetQueueService;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.delta.application.DeltaCheckpointRebuildService;
import com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService;
import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointDownloadResponseDto;
import com.bitbi.dfm.delta.presentation.dto.BatchParquetArtifactResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSegmentResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSyncStateResponseDto;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeRequestDto;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final BatchParquetQueueService batchParquetQueueService;
    private final DeltaRebaselineCancellationService cancellationService;
    private final DeltaCheckpointQueryService checkpointQueryService;
    private final DeltaCheckpointRebuildService checkpointRebuildService;
    private final ChangelogSegmentService changelogSegmentService;
    private final DeltaSiteWipeService wipeService;
    private final SiteService siteService;

    public DeltaSyncAdminController(DeltaSyncStateService syncStateService,
                                    BatchParquetQueueService batchParquetQueueService,
                                    DeltaRebaselineCancellationService cancellationService,
                                    DeltaCheckpointQueryService checkpointQueryService,
                                    DeltaCheckpointRebuildService checkpointRebuildService,
                                    ChangelogSegmentService changelogSegmentService,
                                    DeltaSiteWipeService wipeService,
                                    SiteService siteService) {
        this.syncStateService = syncStateService;
        this.batchParquetQueueService = batchParquetQueueService;
        this.cancellationService = cancellationService;
        this.checkpointQueryService = checkpointQueryService;
        this.checkpointRebuildService = checkpointRebuildService;
        this.changelogSegmentService = changelogSegmentService;
        this.wipeService = wipeService;
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
                    + "rebaseline/rebuild flags, plus snapshotInProgress when a FULL_SNAPSHOT session is uploading. "
                    + "404 when the Delta client has never connected (no sync state row)."
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
        boolean snapshotInProgress = cancellationService.isSnapshotSessionOpen(siteId);
        return syncStateService.findSyncState(siteId)
                .map(state -> ResponseEntity.ok(DeltaSyncStateResponseDto.fromEntity(state, snapshotInProgress)))
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
     * List the durable completed-batch Parquet work rows for one site and batch.
     *
     * @param siteId  site identifier
     * @param batchId batch identifier
     * @return queue rows sorted by table name
     */
    @GetMapping("/batches/{batchId}/parquet-artifacts")
    @Operation(
            summary = "List batch Parquet queue artifacts (admin)",
            description = "Returns operational state for each completed-batch table artifact: status, attempts, "
                    + "last error and timestamps. Storage keys and worker claim tokens are never exposed."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Artifact queue rows retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<BatchParquetArtifactResponseDto>> listBatchParquetArtifacts(
            @PathVariable UUID siteId, @PathVariable UUID batchId) {
        siteService.getSite(siteId);
        List<BatchParquetArtifactResponseDto> response = batchParquetQueueService
                .list(siteId, batchId).stream()
                .map(BatchParquetArtifactResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Reset one abandoned or expired-building artifact to a fresh pending lifecycle.
     *
     * @param siteId      site identifier
     * @param batchId     batch identifier
     * @param artifactId  artifact identifier
     * @param httpRequest request context recorded in the audit trail
     * @return the reset artifact projection
     */
    @PostMapping("/batches/{batchId}/parquet-artifacts/{artifactId}/requeue")
    @Operation(
            summary = "Requeue a batch Parquet artifact (admin)",
            description = "Resets ABANDONED, or BUILDING after its configured lease expires, to PENDING with "
                    + "zero attempts and cleared claim/error state. The action is written to the admin audit log."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Artifact requeued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BatchParquetArtifactResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site or route-constrained artifact not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Artifact state is not recoverable or its lease is live",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<BatchParquetArtifactResponseDto> requeueBatchParquetArtifact(
            @PathVariable UUID siteId, @PathVariable UUID batchId, @PathVariable UUID artifactId,
            HttpServletRequest httpRequest) {
        Site site = siteService.getSite(siteId);
        try {
            return ResponseEntity.ok(BatchParquetArtifactResponseDto.fromEntity(
                    batchParquetQueueService.requeue(site, batchId, artifactId,
                            ipAddress(httpRequest), httpRequest.getHeader("User-Agent"))));
        } catch (BatchParquetQueueService.ArtifactNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (BatchParquetQueueService.ArtifactNotRequeueableException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * Issue a fresh presigned download URL for one checkpoint file of any site.
     * <p>
     * GET /api/v1/sites/{siteId}/delta/checkpoints/{tableName}/download?format=parquet
     * </p>
     *
     * @param siteId    site identifier
     * @param tableName checkpoint table name
     * @param format    file format: {@code parquet} ({@code csv} is retired — #113)
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
            @ApiResponse(responseCode = "410", description = "Retired format: the checkpoint CSV snapshot is no longer produced",
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
        boolean queued = checkpointRebuildService.requestRebuild(siteId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", queued ? "scheduled" : "already-queued"));
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
                    + "answered NEED_REBASELINE and re-sends a full snapshot. The flag is consumed when that "
                    + "FULL_SNAPSHOT session commits, so it stays raised while the snapshot uploads and a "
                    + "snapshot that drops part-way re-arms a clean retry."
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
     * Take back a pending re-baseline request for any site (issue #84).
     * <p>
     * DELETE /api/v1/sites/{siteId}/delta/rebaseline
     * </p>
     *
     * @param siteId site identifier
     * @return 200 OK with the cancellation outcome — see
     * {@link com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService.Outcome}
     */
    @DeleteMapping("/rebaseline")
    @Operation(
            summary = "Cancel a requested full re-baseline (admin)",
            description = "Clears the persistent rebaseline_requested flag without touching the watermark, "
                    + "checkpoints or segments: GetSyncState answers PROCEED again and the client resumes "
                    + "ordinary delta from its watermark. Idempotent. Status: 'cancelled' when the request was "
                    + "called off before the client was ever told; 'snapshot-in-progress' when a FULL_SNAPSHOT "
                    + "session is uploading, which keeps its own intent and replaces the baseline regardless; "
                    + "'client-notified' when the client already holds NEED_REBASELINE and may start at any "
                    + "moment; 'not-requested' when nothing was pending."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Re-baseline cancelled, too late, or none was pending",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Map<String, String>> cancelRebaseline(@PathVariable UUID siteId) {
        siteService.getSite(siteId); // 404 when the site does not exist
        return ResponseEntity.ok(Map.of("status", cancellationService.cancel(siteId).status()));
    }

    /**
     * Destroy all server-side history of any site (issue #89).
     * <p>
     * POST /api/v1/sites/{siteId}/delta/wipe
     * </p>
     *
     * @param siteId  site identifier
     * @param request confirmation body — {@code confirm} must equal the site's name
     * @return 200 with the wipe summary, or 409 when a session is running / one committed mid-wipe
     */
    @PostMapping("/wipe")
    @Operation(
            summary = "Wipe all history of a site (admin)",
            description = "Irreversibly deletes the site's batches, uploaded files, changelog segments, "
                    + "checkpoints, schema, plugin SQL and error logs, leaving the site itself (and its "
                    + "credentials) intact. The sync state is reset and its generation bumped, which tells "
                    + "the Delta client to drop its local journal, reset its seq counter to zero and "
                    + "re-submit its schema; the client is answered NEED_REBASELINE as well, so an old "
                    + "client still recovers. Bit BI delta baselines are re-captured automatically after "
                    + "the first post-wipe checkpoint. The body must echo the site's name."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "History wiped",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteHistoryWipeResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Confirmation missing or not equal to the site's name",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "status=session-in-progress (stop the client and retry) "
                    + "or status=concurrent-session (a batch committed mid-wipe; retry)",
                    content = @Content(mediaType = "application/json"))
    })
    public ResponseEntity<Object> wipeHistory(@PathVariable UUID siteId,
                                              @RequestBody(required = false) SiteHistoryWipeRequestDto request,
                                              HttpServletRequest httpRequest) {
        Site site = siteService.getSite(siteId); // 404 when the site does not exist
        return SiteHistoryWipeEndpoints.wipe(wipeService, site, request,
                DeltaSiteWipeService.Initiator.ADMIN, httpRequest);
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

    private static String ipAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
