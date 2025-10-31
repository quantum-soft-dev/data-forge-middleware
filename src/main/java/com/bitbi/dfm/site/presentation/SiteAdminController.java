package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.CreateSiteRequestDto;
import com.bitbi.dfm.site.presentation.dto.SiteCreationResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteStatisticsDto;
import com.bitbi.dfm.site.presentation.dto.UpdateSiteRequestDto;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for site administration (Admin UI API).
 * <p>
 * Provides admin endpoints for site CRUD operations.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 * <p>
 * URL change from v2.x: /admin/sites → /api/admin/sites (breaking change)
 * URL change from v2.x: /admin/accounts/{accountId}/sites → /api/admin/accounts/{accountId}/sites (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Sites", description = "Site administration endpoints")
public class SiteAdminController {

    private static final Logger logger = LoggerFactory.getLogger(SiteAdminController.class);

    private final SiteService siteService;
    private final AccountStatisticsService accountStatisticsService;
    private final AdminActionLogRepository adminActionLogRepository;

    public SiteAdminController(SiteService siteService, AccountStatisticsService accountStatisticsService,
                               AdminActionLogRepository adminActionLogRepository) {
        this.siteService = siteService;
        this.accountStatisticsService = accountStatisticsService;
        this.adminActionLogRepository = adminActionLogRepository;
    }

    /**
     * Create new site for account.
     * <p>
     * POST /admin/accounts/{accountId}/sites
     * </p>
     *
     * @param accountId account identifier
     * @param request   site details (domain, displayName)
     * @return created site response with plaintext client secret
     */
    @Operation(
            summary = "Create new site",
            description = "Creates a new site for an account with domain and display name. Returns site details including plaintext client secret (only returned once)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Site created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteCreationResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Site already exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/api/admin/accounts/{accountId}/sites")
    public ResponseEntity<SiteCreationResponseDto> createSite(
            @PathVariable("accountId") UUID accountId,
            @Valid @RequestBody CreateSiteRequestDto request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        logger.info("Creating site: accountId={}, domain={}, displayName={}", accountId, request.domain(), request.displayName());

        try {
            SiteService.SiteCreationResult result = siteService.createSite(accountId, request.domain(), request.displayName());

            // Log successful site creation
            logAdminAction(
                    AdminActionType.CREATE_SITE,
                    accountId,
                    result.site().getId(),
                    authentication,
                    httpRequest,
                    null
            );

            SiteCreationResponseDto response = SiteCreationResponseDto.fromCreationResult(result);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            // Log failed site creation
            logAdminAction(
                    AdminActionType.CREATE_SITE,
                    accountId,
                    null,
                    authentication,
                    httpRequest,
                    e.getMessage()
            );
            throw e;
        }
    }

    /**
     * Get site by ID.
     * <p>
     * GET /admin/sites/{id}
     * </p>
     *
     * @param siteId site identifier
     * @return site response
     */
    @Operation(
            summary = "Get site by ID",
            description = "Retrieves site details by site ID."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Site found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/api/admin/sites/{id}")
    public ResponseEntity<SiteResponseDto> getSite(@PathVariable("id") UUID siteId) {
        Site site = siteService.getSite(siteId);
        SiteResponseDto response = SiteResponseDto.fromEntity(site);
        return ResponseEntity.ok(response);
    }

    /**
     * List all sites with pagination (admin endpoint).
     * <p>
     * GET /admin/sites?page=0&size=20&sort=createdAt,desc
     * </p>
     *
     * @param page page number (default: 0)
     * @param size page size (default: 20)
     * @param sort sort field and direction (default: createdAt,desc)
     * @return paginated list of sites
     */
    @Operation(
            summary = "List all sites",
            description = "Retrieves a paginated list of all sites across all accounts with sorting support."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sites retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    })
    @GetMapping("/api/admin/sites")
    public ResponseEntity<PageResponseDto<SiteResponseDto>> listAllSites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        // Parse sort parameter
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        Sort.Direction sortDirection = sortParams.length > 1 && "asc".equalsIgnoreCase(sortParams[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Create pageable
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortField));

        // Get paginated sites
        Page<Site> sitePage = siteService.listAllSites(pageable);

        // Convert to response DTO
        PageResponseDto<SiteResponseDto> response = PageResponseDto.of(sitePage, SiteResponseDto::fromEntity);

        return ResponseEntity.ok(response);
    }

    /**
     * List sites for account.
     * <p>
     * GET /admin/accounts/{accountId}/sites
     * </p>
     *
     * @param accountId account identifier
     * @return list of sites
     */
    @Operation(
            summary = "List sites by account",
            description = "Retrieves all sites belonging to a specific account."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sites retrieved successfully",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/api/admin/accounts/{accountId}/sites")
    public ResponseEntity<List<SiteResponseDto>> listSitesByAccount(@PathVariable("accountId") UUID accountId) {
        List<Site> sites = siteService.listSitesByAccount(accountId);

        List<SiteResponseDto> siteList = sites.stream()
                .map(SiteResponseDto::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(siteList);
    }

    /**
     * Update site.
     * <p>
     * PUT /admin/sites/{id}
     * </p>
     *
     * @param siteId  site identifier
     * @param request site update details (displayName)
     * @return updated site response
     */
    @Operation(
            summary = "Update site",
            description = "Updates site display name."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Site updated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PutMapping("/api/admin/sites/{id}")
    public ResponseEntity<SiteResponseDto> updateSite(
            @PathVariable("id") UUID siteId,
            @Valid @RequestBody UpdateSiteRequestDto request) {

        logger.info("Updating site: siteId={}, displayName={}", siteId, request.displayName());

        Site site = siteService.updateSite(siteId, request.displayName());

        SiteResponseDto response = SiteResponseDto.fromEntity(site);
        return ResponseEntity.ok(response);
    }

    /**
     * Activate a site (admin operation).
     * <p>
     * POST /admin/accounts/{accountId}/sites/{siteId}/activate
     * </p>
     *
     * @param accountId account identifier
     * @param siteId site identifier
     * @return activated site entity
     */
    @Operation(
            summary = "Activate a site",
            description = "Reactivates a previously deactivated site by setting isActive=true."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Site activated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/api/admin/accounts/{accountId}/sites/{siteId}/activate")
    public ResponseEntity<SiteResponseDto> activateSite(
            @PathVariable("accountId") UUID accountId,
            @PathVariable("siteId") UUID siteId) {

        logger.info("Activating site: siteId={}, accountId={}", siteId, accountId);

        Site site = siteService.reactivateSite(siteId);
        SiteResponseDto response = SiteResponseDto.fromEntity(site);

        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate a site (admin operation).
     * <p>
     * POST /admin/accounts/{accountId}/sites/{siteId}/deactivate
     * </p>
     *
     * @param accountId account identifier
     * @param siteId site identifier
     * @return no content response
     */
    @Operation(
            summary = "Deactivate a site",
            description = "Soft-deletes a site by setting isActive=false."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Site deactivated successfully"),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/api/admin/accounts/{accountId}/sites/{siteId}/deactivate")
    public ResponseEntity<Void> deactivateSiteForAccount(
            @PathVariable("accountId") UUID accountId,
            @PathVariable("siteId") UUID siteId) {

        logger.info("Deactivating site: siteId={}, accountId={}", siteId, accountId);

        siteService.deactivateSite(siteId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Delete a site (admin operation, soft delete).
     * <p>
     * DELETE /admin/accounts/{accountId}/sites/{siteId}
     * </p>
     *
     * @param accountId account identifier
     * @param siteId site identifier
     * @return no content response
     */
    @Operation(
            summary = "Delete a site",
            description = "Soft-deletes a site by setting isActive=false."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Site deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @DeleteMapping("/api/admin/accounts/{accountId}/sites/{siteId}")
    public ResponseEntity<Void> deleteSiteForAccount(
            @PathVariable("accountId") UUID accountId,
            @PathVariable("siteId") UUID siteId) {

        logger.info("Deleting site: siteId={}, accountId={}", siteId, accountId);

        siteService.deactivateSite(siteId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Deactivate site.
     * <p>
     * DELETE /admin/sites/{id}
     * </p>
     *
     * @param siteId site identifier
     * @return no content response
     */
    @Operation(
            summary = "Deactivate site",
            description = "Soft-deletes a site by setting isActive=false."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Site deactivated successfully"),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @DeleteMapping("/api/admin/sites/{id}")
    public ResponseEntity<Void> deactivateSite(@PathVariable("id") UUID siteId) {
        logger.info("Deactivating site: siteId={}", siteId);

        siteService.deactivateSite(siteId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Get site statistics.
     * <p>
     * GET /admin/sites/{id}/statistics
     * </p>
     *
     * @param siteId site identifier
     * @return site statistics
     */
    @Operation(
            summary = "Get site statistics",
            description = "Retrieves site statistics including batches count, files, and storage size."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteStatisticsDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/api/admin/sites/{id}/statistics")
    public ResponseEntity<SiteStatisticsDto> getSiteStatistics(@PathVariable("id") UUID siteId) {
        Map<String, Object> statistics = accountStatisticsService.getSiteStatistics(siteId);
        SiteStatisticsDto response = SiteStatisticsDto.fromMap(statistics);
        return ResponseEntity.ok(response);
    }

    // ========== Helper Methods ==========

    /**
     * Log admin action to admin_action_logs table.
     *
     * @param actionType      type of action (CREATE_SITE, DEACTIVATE_SITE, etc.)
     * @param targetAccountId account ID being acted upon
     * @param targetSiteId    site ID being acted upon (null for account-level actions)
     * @param authentication  Spring Security authentication object
     * @param httpRequest     HTTP request for IP and user agent extraction
     * @param errorMessage    error message (null for successful actions)
     */
    private void logAdminAction(AdminActionType actionType, UUID targetAccountId, UUID targetSiteId,
                                Authentication authentication, HttpServletRequest httpRequest, String errorMessage) {
        try {
            UUID adminAccountId = extractAccountIdFromJwt(authentication);
            String ipAddress = extractIpAddress(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            AdminActionLog log;
            if (errorMessage == null) {
                // Success
                log = AdminActionLog.successForSite(actionType, targetAccountId, targetSiteId,
                        adminAccountId, ipAddress, userAgent);
            } else {
                // Failure
                log = AdminActionLog.failureForSite(actionType, targetAccountId, targetSiteId,
                        adminAccountId, errorMessage, ipAddress, userAgent);
            }

            adminActionLogRepository.save(log);
            logger.info("Admin action logged: actionType={}, targetAccountId={}, targetSiteId={}, adminAccountId={}, status={}",
                    actionType, targetAccountId, targetSiteId, adminAccountId, errorMessage == null ? "SUCCESS" : "FAILED");

        } catch (Exception e) {
            logger.error("Failed to log admin action: actionType={}, targetAccountId={}, targetSiteId={}",
                    actionType, targetAccountId, targetSiteId, e);
        }
    }

    /**
     * Extract account ID from JWT token.
     *
     * @param authentication Spring Security authentication object
     * @return account ID from JWT sub claim
     */
    private UUID extractAccountIdFromJwt(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getClaimAsString("sub");
            return UUID.fromString(sub);
        }
        return null;
    }

    /**
     * Extract IP address from HTTP request (handles X-Forwarded-For header).
     *
     * @param request HTTP request
     * @return IP address
     */
    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
