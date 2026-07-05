package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointDownloadResponseDto;
import com.bitbi.dfm.delta.presentation.dto.DeltaCheckpointResponseDto;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    private final DeltaSyncStateService syncStateService;
    private final DeltaCheckpointQueryService checkpointQueryService;
    private final SiteService siteService;

    public DeltaSyncAdminController(DeltaSyncStateService syncStateService,
                                    DeltaCheckpointQueryService checkpointQueryService,
                                    SiteService siteService) {
        this.syncStateService = syncStateService;
        this.checkpointQueryService = checkpointQueryService;
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
}
