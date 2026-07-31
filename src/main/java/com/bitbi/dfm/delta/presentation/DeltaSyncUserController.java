package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService;
import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.application.DeltaSegmentParquetQueryService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointDownloadResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaSyncStateResponseDto;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeRequestDto;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeResponseDto;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final DeltaRebaselineCancellationService cancellationService;
    private final DeltaCheckpointQueryService checkpointQueryService;
    private final DeltaSegmentParquetQueryService segmentParquetQueryService;
    private final DeltaSiteWipeService wipeService;
    private final SiteService siteService;
    private final AuthorizationHelper authorizationHelper;

    public DeltaSyncUserController(DeltaSyncStateService syncStateService,
                                   DeltaRebaselineCancellationService cancellationService,
                                   DeltaCheckpointQueryService checkpointQueryService,
                                   DeltaSegmentParquetQueryService segmentParquetQueryService,
                                   DeltaSiteWipeService wipeService,
                                   SiteService siteService,
                                   AuthorizationHelper authorizationHelper) {
        this.syncStateService = syncStateService;
        this.cancellationService = cancellationService;
        this.checkpointQueryService = checkpointQueryService;
        this.segmentParquetQueryService = segmentParquetQueryService;
        this.wipeService = wipeService;
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
                    + "rebaseline/rebuild flags, plus snapshotInProgress when a FULL_SNAPSHOT session is uploading. "
                    + "404 when the Delta client has never connected (no sync state row)."
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
        boolean snapshotInProgress = cancellationService.isSnapshotSessionOpen(siteId);
        return syncStateService.findSyncState(siteId)
                .map(state -> ResponseEntity.ok(DeltaSyncStateResponseDto.fromEntity(state, snapshotInProgress)))
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
                    + "answered NEED_REBASELINE and re-sends a full snapshot. The flag is consumed when that "
                    + "FULL_SNAPSHOT session commits, so it stays raised while the snapshot uploads and a "
                    + "snapshot that drops part-way re-arms a clean retry."
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
     * Take back a pending re-baseline request for an owned site (issue #84).
     * <p>
     * DELETE /api/v1/account/sites/{siteId}/delta/rebaseline
     * </p>
     *
     * @param siteId site identifier
     * @return 200 OK with the cancellation outcome — see
     * {@link com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService.Outcome}
     */
    @DeleteMapping("/rebaseline")
    @Operation(
            summary = "Cancel a requested full re-baseline",
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
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Map<String, String>> cancelRebaseline(@PathVariable UUID siteId) {
        requireOwnedSite(siteId);
        DeltaRebaselineCancellationService.Outcome outcome = cancellationService.cancel(siteId);
        logger.info("Full re-baseline cancellation by owner: siteId={}, outcome={}", siteId, outcome.status());
        return ResponseEntity.ok(Map.of("status", outcome.status()));
    }


    /**
     * Destroy all server-side history of an owned site (issue #89).
     * <p>
     * POST /api/v1/account/sites/{siteId}/delta/wipe
     * </p>
     *
     * @param siteId  site identifier
     * @param request confirmation body — {@code confirm} must equal the site's name
     * @return 200 with the wipe summary, or 409 when a session is running / one committed mid-wipe
     */
    @PostMapping("/wipe")
    @Operation(
            summary = "Wipe all history of a site",
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
            @ApiResponse(responseCode = "403", description = "Site does not belong to the authenticated account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "status=session-in-progress (stop the client and retry) "
                    + "or status=concurrent-session (a batch committed mid-wipe; retry)",
                    content = @Content(mediaType = "application/json"))
    })
    public ResponseEntity<Object> wipeHistory(@PathVariable UUID siteId,
                                              @RequestBody(required = false) SiteHistoryWipeRequestDto request) {
        Site site = requireOwnedSite(siteId);
        logger.warn("Site history wipe requested by owner: siteId={}", siteId);
        return SiteHistoryWipeEndpoints.wipe(wipeService, site, request,
                DeltaSiteWipeService.Initiator.OWNER);
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
