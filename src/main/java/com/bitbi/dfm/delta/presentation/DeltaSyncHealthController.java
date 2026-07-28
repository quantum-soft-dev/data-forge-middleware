package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaSyncHealthService;
import com.bitbi.dfm.delta.presentation.dto.DeltaSyncHealthResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Bulk sync-health endpoint for the site list (feature 023 — Delta Sync UI, B10).
 *
 * <p>The site-list health badge polls one of these endpoints every ~30 s; both return the raw
 * health inputs for <b>all</b> Delta v2 sites of an account in a single query — N per-site
 * requests are explicitly not an option here.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@Tag(name = "UI/Admin API - Delta Sync Health", description = "Bulk sync health for the site list")
@SecurityRequirement(name = "oauth2")
public class DeltaSyncHealthController {

    private final DeltaSyncHealthService healthService;
    private final AuthorizationHelper authorizationHelper;

    public DeltaSyncHealthController(DeltaSyncHealthService healthService,
                                     AuthorizationHelper authorizationHelper) {
        this.healthService = healthService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Bulk sync health for all Delta v2 sites of the authenticated account.
     * <p>
     * GET /api/v1/account/sites/delta/health
     * </p>
     *
     * @return one entry per V2 site (empty array when the account has none)
     */
    @GetMapping(ApiRoutes.SITES_USER + "/delta/health")
    @Operation(
            summary = "Bulk delta sync health (owner)",
            description = "Returns watermark, checkpoint pointer and updatedAt for every site of the "
                    + "authenticated account in one response. hasSyncState=false marks sites "
                    + "whose client never connected."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Health entries retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "User not authenticated or accountId not found in JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<DeltaSyncHealthResponseDto>> getAccountHealth() {
        UUID accountId = authorizationHelper.getAuthenticatedAccountId();
        return ResponseEntity.ok(toDtos(accountId));
    }

    /**
     * Bulk sync health for all Delta v2 sites of any account (admin).
     * <p>
     * GET /api/v1/accounts/{accountId}/sites/delta/health
     * </p>
     *
     * @param accountId account identifier
     * @return one entry per V2 site (empty array when the account has none)
     */
    @GetMapping(ApiRoutes.SITES_BY_ACCOUNT + "/delta/health")
    @Operation(
            summary = "Bulk delta sync health (admin)",
            description = "Admin variant of the bulk health endpoint for any account's site list."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Health entries retrieved",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<DeltaSyncHealthResponseDto>> getAccountHealthAsAdmin(@PathVariable UUID accountId) {
        return ResponseEntity.ok(toDtos(accountId));
    }

    private List<DeltaSyncHealthResponseDto> toDtos(UUID accountId) {
        return healthService.listHealthForAccount(accountId).stream()
                .map(DeltaSyncHealthResponseDto::of)
                .toList();
    }
}
