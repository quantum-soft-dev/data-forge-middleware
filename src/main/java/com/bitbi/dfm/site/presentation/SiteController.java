package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.PasswordGenerator;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.CreateSiteRequestDto;
import com.bitbi.dfm.site.presentation.dto.SiteCreationResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for user site management operations.
 * <p>
 * Provides endpoints for authenticated users to manage their own sites:
 * - Create site with password (manual or generated)
 * - List all active sites (sorted by creation date)
 * - Activate/deactivate sites
 * - Delete sites (soft delete)
 * <p>
 * Feature: 007-adding-a-site (US1, US2, US3)
 * Endpoints: POST /api/sites, GET /api/sites, POST /api/sites/{id}/activate, etc.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/sites")
@Tag(name = "Site Management", description = "User site management operations")
@SecurityRequirement(name = "bearerAuth")
public class SiteController {

    private static final Logger logger = LoggerFactory.getLogger(SiteController.class);

    private final SiteService siteService;
    private final PasswordGenerator passwordGenerator;

    public SiteController(SiteService siteService, PasswordGenerator passwordGenerator) {
        this.siteService = siteService;
        this.passwordGenerator = passwordGenerator;
    }

    /**
     * Create a new site for the authenticated user.
     * <p>
     * US1: User creates own site with generated or manual password.
     * FR-002: Domain uniqueness enforced per account.
     * FR-015: Domain validation (alphanumeric + dots/hyphens).
     * FR-016: Password minimum 8 characters.
     * <p>
     * POST /api/sites
     *
     * @param request      Site creation request (domain + password optional)
     * @param authentication Spring Security authentication object
     * @return Created site with plaintext password (only returned once)
     */
    @PostMapping
    @Operation(summary = "Create a new site")
    public ResponseEntity<SiteCreationResponseDto> createSite(
            @Valid @RequestBody CreateSiteRequestDto request,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);
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
        SiteCreationResponseDto response = SiteCreationResponseDto.fromCreationResult(result);

        logger.info("Site created successfully: siteId={}, domain={}", result.site().getId(), request.domain());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all active sites for the authenticated user.
     * <p>
     * US1: User views site list sorted by creation date (newest first).
     * FR-009: Only active sites returned.
     * <p>
     * GET /api/sites
     *
     * @param authentication Spring Security authentication object
     * @return List of active sites sorted by createdAt DESC
     */
    @GetMapping
    @Operation(summary = "List user's active sites")
    public ResponseEntity<List<SiteResponseDto>> listUserSites(Authentication authentication) {

        UUID accountId = extractAccountId(authentication);
        logger.info("Listing sites for account: accountId={}", accountId);

        List<Site> sites = siteService.listAccountSites(accountId);
        List<SiteResponseDto> response = sites.stream()
                .map(SiteResponseDto::fromEntity)
                .toList();

        logger.info("Found {} active sites for account: accountId={}", sites.size(), accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate a site (user operation).
     * <p>
     * US2: User deactivates own site.
     * FR-004: Soft delete via isActive flag.
     * FR-021: Site data preserved after deactivation.
     * <p>
     * POST /api/sites/{siteId}/deactivate
     *
     * @param siteId         Site identifier
     * @param authentication Spring Security authentication object
     * @return No content (204)
     */
    @PostMapping("/{siteId}/deactivate")
    @Operation(summary = "Deactivate a site")
    public ResponseEntity<Void> deactivateSite(
            @PathVariable UUID siteId,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);
        logger.info("Deactivating site: siteId={}, accountId={}", siteId, accountId);

        // Verify site belongs to user
        Site site = siteService.getSite(siteId);
        if (!site.getAccountId().equals(accountId)) {
            throw new UnauthorizedSiteAccessException("Site does not belong to authenticated user");
        }

        siteService.deactivateSite(siteId);

        logger.info("Site deactivated: siteId={}", siteId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Activate a site (user operation).
     * <p>
     * US2: User activates previously deactivated site.
     * FR-005: Reactivate site by setting isActive=true.
     * <p>
     * POST /api/sites/{siteId}/activate
     *
     * @param siteId         Site identifier
     * @param authentication Spring Security authentication object
     * @return Activated site entity
     */
    @PostMapping("/{siteId}/activate")
    @Operation(summary = "Activate a site")
    public ResponseEntity<SiteResponseDto> activateSite(
            @PathVariable UUID siteId,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);
        logger.info("Activating site: siteId={}, accountId={}", siteId, accountId);

        // Verify site belongs to user
        Site site = siteService.getSite(siteId);
        if (!site.getAccountId().equals(accountId)) {
            throw new UnauthorizedSiteAccessException("Site does not belong to authenticated user");
        }

        Site activatedSite = siteService.reactivateSite(siteId);
        SiteResponseDto response = SiteResponseDto.fromEntity(activatedSite);

        logger.info("Site activated: siteId={}", siteId);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a site (user operation, soft delete).
     * <p>
     * US3: User deletes own site (soft delete).
     * FR-006: Soft delete preserves data.
     * FR-024: Batch and upload history preserved.
     * <p>
     * DELETE /api/sites/{siteId}
     *
     * @param siteId         Site identifier
     * @param authentication Spring Security authentication object
     * @return No content (204)
     */
    @DeleteMapping("/{siteId}")
    @Operation(summary = "Delete a site (soft delete)")
    public ResponseEntity<Void> deleteSite(
            @PathVariable UUID siteId,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);
        logger.info("Deleting site: siteId={}, accountId={}", siteId, accountId);

        // Verify site belongs to user
        Site site = siteService.getSite(siteId);
        if (!site.getAccountId().equals(accountId)) {
            throw new UnauthorizedSiteAccessException("Site does not belong to authenticated user");
        }

        siteService.deleteSite(siteId);

        logger.info("Site deleted: siteId={}", siteId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extract account ID from Spring Security authentication.
     *
     * @param authentication Spring Security authentication object
     * @return Account ID
     */
    private UUID extractAccountId(Authentication authentication) {
        // TODO: Extract from JWT claims or Keycloak principal
        // For now, parse from authentication name (temporary)
        String name = authentication.getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid account ID in authentication: " + name);
        }
    }

    /**
     * Exception thrown when user tries to access site they don't own.
     */
    public static class UnauthorizedSiteAccessException extends RuntimeException {
        public UnauthorizedSiteAccessException(String message) {
            super(message);
        }
    }
}
