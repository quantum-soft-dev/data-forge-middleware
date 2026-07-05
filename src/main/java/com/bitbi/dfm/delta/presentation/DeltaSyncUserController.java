package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final SiteService siteService;
    private final AuthorizationHelper authorizationHelper;

    public DeltaSyncUserController(DeltaSyncStateService syncStateService,
                                   SiteService siteService,
                                   AuthorizationHelper authorizationHelper) {
        this.syncStateService = syncStateService;
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
