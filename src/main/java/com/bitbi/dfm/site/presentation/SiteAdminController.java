package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for site administration.
 * <p>
 * Provides admin endpoints for site CRUD operations.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class SiteAdminController {

    private static final Logger logger = LoggerFactory.getLogger(SiteAdminController.class);

    private final SiteService siteService;
    private final AccountStatisticsService accountStatisticsService;

    public SiteAdminController(SiteService siteService, AccountStatisticsService accountStatisticsService) {
        this.siteService = siteService;
        this.accountStatisticsService = accountStatisticsService;
    }

    /**
     * Create new site for account.
     * <p>
     * POST /admin/accounts/{accountId}/sites
     * </p>
     *
     * @param accountId account identifier
     * @param request   site details (domain, displayName)
     * @return created site response
     */
    @PostMapping("/admin/accounts/{accountId}/sites")
    public ResponseEntity<Map<String, Object>> createSite(
            @PathVariable("accountId") UUID accountId,
            @RequestBody Map<String, String> request) {

        try {
            String domain = request.get("domain");
            String displayName = request.get("displayName");

            if (domain == null || domain.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Domain is required"));
            }

            if (displayName == null || displayName.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Display name is required"));
            }

            logger.info("Creating site: accountId={}, domain={}, displayName={}", accountId, domain, displayName);

            SiteService.SiteCreationResult result = siteService.createSite(accountId, domain, displayName);

            // Special response for site creation - includes plaintext secret (one-time only)
            SiteResponseDto dto = SiteResponseDto.fromEntity(result.site());
            Map<String, Object> response = new HashMap<>();
            response.put("id", dto.id());
            response.put("accountId", dto.accountId());
            response.put("domain", dto.domain());
            response.put("name", dto.name());
            response.put("isActive", dto.isActive());
            response.put("createdAt", dto.createdAt());
            response.put("clientSecret", result.plaintextSecret()); // Only shown at creation

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (SiteService.SiteAlreadyExistsException e) {
            logger.warn("Site already exists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse(e.getMessage()));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid site data: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error creating site", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to create site"));
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
    @GetMapping("/admin/sites/{id}")
    public ResponseEntity<?> getSite(@PathVariable("id") UUID siteId) {
        try {
            Site site = siteService.getSite(siteId);
            SiteResponseDto response = SiteResponseDto.fromEntity(site);
            return ResponseEntity.ok(response);

        } catch (SiteService.SiteNotFoundException e) {
            logger.warn("Site not found: {}", siteId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Site not found"));

        } catch (Exception e) {
            logger.error("Error getting site: siteId={}", siteId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve site"));
        }
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
    @GetMapping("/admin/accounts/{accountId}/sites")
    public ResponseEntity<?> listSitesByAccount(@PathVariable("accountId") UUID accountId) {
        try {
            List<Site> sites = siteService.listSitesByAccount(accountId);

            List<SiteResponseDto> siteList = sites.stream()
                    .map(SiteResponseDto::fromEntity)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(siteList);

        } catch (Exception e) {
            logger.error("Error listing sites: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to list sites"));
        }
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
    @PutMapping("/admin/sites/{id}")
    public ResponseEntity<?> updateSite(
            @PathVariable("id") UUID siteId,
            @RequestBody Map<String, String> request) {

        try {
            String displayName = request.get("displayName");

            if (displayName == null || displayName.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Display name is required"));
            }

            logger.info("Updating site: siteId={}, displayName={}", siteId, displayName);

            Site site = siteService.updateSite(siteId, displayName);

            SiteResponseDto response = SiteResponseDto.fromEntity(site);
            return ResponseEntity.ok(response);

        } catch (SiteService.SiteNotFoundException e) {
            logger.warn("Site not found: {}", siteId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Site not found"));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid site data: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error updating site: siteId={}", siteId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to update site"));
        }
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
    @DeleteMapping("/admin/sites/{id}")
    public ResponseEntity<Map<String, Object>> deactivateSite(@PathVariable("id") UUID siteId) {
        try {
            logger.info("Deactivating site: siteId={}", siteId);

            siteService.deactivateSite(siteId);

            return ResponseEntity.noContent().build();

        } catch (SiteService.SiteNotFoundException e) {
            logger.warn("Site not found: {}", siteId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Site not found"));

        } catch (Exception e) {
            logger.error("Error deactivating site: siteId={}", siteId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to deactivate site"));
        }
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
    @GetMapping("/admin/sites/{id}/statistics")
    public ResponseEntity<Map<String, Object>> getSiteStatistics(@PathVariable("id") UUID siteId) {
        try {
            Map<String, Object> statistics = accountStatisticsService.getSiteStatistics(siteId);
            return ResponseEntity.ok(statistics);

        } catch (Exception e) {
            logger.error("Error getting site statistics: siteId={}", siteId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve statistics"));
        }
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
