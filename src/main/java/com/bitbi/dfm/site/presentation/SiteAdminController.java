package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.CreateSiteRequestDto;
import com.bitbi.dfm.site.presentation.dto.SiteCreationResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteStatisticsDto;
import com.bitbi.dfm.site.presentation.dto.UpdateSiteRequestDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
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
     * @return created site response with plaintext client secret
     */
    @PostMapping("/api/admin/accounts/{accountId}/sites")
    public ResponseEntity<SiteCreationResponseDto> createSite(
            @PathVariable("accountId") UUID accountId,
            @Valid @RequestBody CreateSiteRequestDto request) {

        logger.info("Creating site: accountId={}, domain={}, displayName={}", accountId, request.domain(), request.displayName());

        SiteService.SiteCreationResult result = siteService.createSite(accountId, request.domain(), request.displayName());

        SiteCreationResponseDto response = SiteCreationResponseDto.fromCreationResult(result);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
     * Deactivate site.
     * <p>
     * DELETE /admin/sites/{id}
     * </p>
     *
     * @param siteId site identifier
     * @return no content response
     */
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
    @GetMapping("/api/admin/sites/{id}/statistics")
    public ResponseEntity<SiteStatisticsDto> getSiteStatistics(@PathVariable("id") UUID siteId) {
        Map<String, Object> statistics = accountStatisticsService.getSiteStatistics(siteId);
        SiteStatisticsDto response = SiteStatisticsDto.fromMap(statistics);
        return ResponseEntity.ok(response);
    }
}
