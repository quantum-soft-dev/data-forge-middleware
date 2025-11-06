package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.PasswordGenerator;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.CreateSiteRequestDto;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import com.bitbi.dfm.site.presentation.dto.UserSiteCreationResponseDto;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for user site management operations (UI/Admin API).
 * <p>
 * Provides endpoints for authenticated users (ROLE_USER) to manage their own sites:
 * - Create site with password (manual or generated)
 * - List all active sites (sorted by creation date)
 * - Activate/deactivate sites
 * - Delete sites (soft delete)
 * </p>
 * <p>
 * API Path: /api/v1/sites (per API Unification Spec 010)
 * Authentication: Keycloak OAuth2 JWT with accountId claim
 * </p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@RestController
@RequestMapping(ApiRoutes.SITES_USER)
@Tag(name = "UI/Admin API - User Sites", description = "User site management operations")
@SecurityRequirement(name = "oauth2")
public class SiteUserController {

    private static final Logger logger = LoggerFactory.getLogger(SiteUserController.class);

    private final SiteService siteService;
    private final PasswordGenerator passwordGenerator;
    private final AuthorizationHelper authorizationHelper;

    public SiteUserController(SiteService siteService, PasswordGenerator passwordGenerator, AuthorizationHelper authorizationHelper) {
        this.siteService = siteService;
        this.passwordGenerator = passwordGenerator;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Create a new site for the authenticated user.
     * <p>
     * POST /api/v1/sites
     * </p>
     *
     * @param request Site creation request (domain, displayName, optional password)
     * @return Created site with plaintext password (only returned once)
     */
    @PostMapping
    @Operation(
            summary = "Create a new site",
            description = "Creates a new site for the authenticated user with optional password. If password not provided, generates secure password automatically."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Site created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserSiteCreationResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "User not authenticated or accountId not found in JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Site with this domain already exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<UserSiteCreationResponseDto> createSite(@Valid @RequestBody CreateSiteRequestDto request) {
        try {
            // Get authenticated account ID from JWT
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            logger.info("Creating site for account: accountId={}, domain={}", accountId, request.domain());

            // Use provided password or generate if null/empty
            String password = (request.password() == null || request.password().isBlank())
                    ? passwordGenerator.generate()
                    : request.password();

            // Create site with password
            SiteService.SiteCreationResult result = siteService.createSite(
                    accountId,
                    request.domain(),
                    request.displayName(),
                    password
            );

            // Build response with site and plaintext password
            UserSiteCreationResponseDto response = UserSiteCreationResponseDto.fromCreationResult(result);

            logger.info("Site created successfully: siteId={}, domain={}", result.site().getId(), request.domain());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized site creation attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * List all sites for the authenticated user.
     * <p>
     * GET /api/v1/sites
     * </p>
     *
     * @return List of all sites (active and inactive) sorted by createdAt DESC
     */
    @GetMapping
    @Operation(
            summary = "List user's sites",
            description = "Retrieves all sites (both active and inactive) for the authenticated user, sorted by creation date (newest first)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sites retrieved successfully",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "User not authenticated or accountId not found in JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<List<SiteResponseDto>> listUserSites() {
        try {
            // Get authenticated account ID from JWT
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            logger.info("Listing sites for account: accountId={}", accountId);

            List<Site> sites = siteService.listSitesByAccount(accountId);
            List<SiteResponseDto> response = sites.stream()
                    .map(SiteResponseDto::fromEntity)
                    .toList();

            logger.info("Found {} sites for account: accountId={}", sites.size(), accountId);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized site list access: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * Deactivate a site (user operation).
     * <p>
     * POST /api/v1/sites/{siteId}/deactivate
     * </p>
     *
     * @param siteId Site identifier
     * @return Deactivated site entity (200 OK)
     */
    @PostMapping("/{siteId}/deactivate")
    @Operation(
            summary = "Deactivate a site",
            description = "Soft-deletes a site by setting isActive=false. Site data is preserved."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Site deactivated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Site does not belong to authenticated user",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<SiteResponseDto> deactivateSite(@PathVariable UUID siteId) {
        try {
            // Get authenticated account ID from JWT
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            logger.info("Deactivating site: siteId={}, accountId={}", siteId, accountId);

            // Verify site belongs to user
            Site site = siteService.getSite(siteId);
            if (!site.getAccountId().equals(accountId)) {
                logger.warn("Unauthorized deactivation attempt: siteId={}, authenticatedAccountId={}, siteAccountId={}",
                        siteId, accountId, site.getAccountId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            siteService.deactivateSite(siteId);

            // Reload and return the deactivated site
            Site deactivatedSite = siteService.getSite(siteId);
            SiteResponseDto response = SiteResponseDto.fromEntity(deactivatedSite);

            logger.info("Site deactivated: siteId={}", siteId);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized deactivation attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * Activate a site (user operation).
     * <p>
     * POST /api/v1/sites/{siteId}/activate
     * </p>
     *
     * @param siteId Site identifier
     * @return Activated site entity
     */
    @PostMapping("/{siteId}/activate")
    @Operation(
            summary = "Activate a site",
            description = "Reactivates a previously deactivated site by setting isActive=true."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Site activated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Site does not belong to authenticated user",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<SiteResponseDto> activateSite(@PathVariable UUID siteId) {
        try {
            // Get authenticated account ID from JWT
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            logger.info("Activating site: siteId={}, accountId={}", siteId, accountId);

            // Verify site belongs to user
            Site site = siteService.getSite(siteId);
            if (!site.getAccountId().equals(accountId)) {
                logger.warn("Unauthorized activation attempt: siteId={}, authenticatedAccountId={}, siteAccountId={}",
                        siteId, accountId, site.getAccountId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Site activatedSite = siteService.reactivateSite(siteId);
            SiteResponseDto response = SiteResponseDto.fromEntity(activatedSite);

            logger.info("Site activated: siteId={}", siteId);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized activation attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * Delete a site (user operation, hard delete).
     * <p>
     * DELETE /api/v1/sites/{siteId}
     * </p>
     *
     * @param siteId Site identifier
     * @return No content (204)
     */
    @DeleteMapping("/{siteId}")
    @Operation(
            summary = "Delete a site",
            description = "Permanently deletes a site and all associated data (batches, uploads, error logs)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Site deleted successfully"),
            @ApiResponse(responseCode = "403", description = "Site does not belong to authenticated user",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<Void> deleteSite(@PathVariable UUID siteId) {
        try {
            // Get authenticated account ID from JWT
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();
            logger.info("Deleting site: siteId={}, accountId={}", siteId, accountId);

            // Verify site belongs to user
            Site site = siteService.getSite(siteId);
            if (!site.getAccountId().equals(accountId)) {
                logger.warn("Unauthorized deletion attempt: siteId={}, authenticatedAccountId={}, siteAccountId={}",
                        siteId, accountId, site.getAccountId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            siteService.deleteSite(siteId);

            logger.info("Site deleted: siteId={}", siteId);
            return ResponseEntity.noContent().build();

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized deletion attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }
}
