package com.bitbi.dfm.clientlog.presentation;

import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.clientlog.application.ClientDiagnosticLogService;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;
import com.bitbi.dfm.clientlog.presentation.dto.ClientLogDownloadResponseDto;
import com.bitbi.dfm.clientlog.presentation.dto.ClientLogListResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for admin client diagnostic log management.
 * <p>
 * Provides paginated listing and presigned download URLs for client logs.
 * Accessible to ROLE_ADMIN (any site) and ROLE_USER (own account's sites only).
 * ROLE_USER access is verified by checking site account ownership.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US5 - Admin Views and Downloads Client Logs
 */
@RestController
@Tag(name = "Admin - Client Logs", description = "Client diagnostic log viewing and download endpoints")
@SecurityRequirement(name = "oauth2")
public class ClientLogAdminController {

    private static final Logger logger = LoggerFactory.getLogger(ClientLogAdminController.class);

    private final ClientDiagnosticLogService logService;
    private final SiteRepository siteRepository;
    private final Auth0Properties auth0Properties;

    public ClientLogAdminController(ClientDiagnosticLogService logService,
                                     SiteRepository siteRepository,
                                     Auth0Properties auth0Properties) {
        this.logService = logService;
        this.siteRepository = siteRepository;
        this.auth0Properties = auth0Properties;
    }

    /**
     * List client diagnostic logs for a site.
     * <p>
     * GET /api/v1/admin/sites/{siteId}/client-logs
     * </p>
     *
     * @param siteId site identifier
     * @param page   page number (default: 0)
     * @param size   page size (default: 20, max: 100)
     * @return paginated list of client logs
     */
    @Operation(
            summary = "List client diagnostic logs",
            description = "Returns paginated list of client log entries for a specific site."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Paginated list of client logs",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientLogListResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping(ApiRoutes.ADMIN_SITES_CLIENT_LOGS)
    public ResponseEntity<ClientLogListResponseDto> listClientLogs(
            @PathVariable("siteId") UUID siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        logger.debug("List client logs: siteId={}, page={}, size={}", siteId, page, size);
        verifySiteAccess(siteId, authentication);

        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize);
        Page<ClientDiagnosticLog> logs = logService.listLogs(siteId, pageable);

        ClientLogListResponseDto response = ClientLogListResponseDto.fromPage(logs);
        return ResponseEntity.ok(response);
    }

    /**
     * Download a client diagnostic log file.
     * <p>
     * GET /api/v1/admin/sites/{siteId}/client-logs/{logId}/download
     * </p>
     * <p>
     * Returns a presigned S3 URL with 15-minute expiry.
     * </p>
     *
     * @param siteId site identifier
     * @param logId  log identifier
     * @return download response with presigned URL
     */
    @Operation(
            summary = "Download client diagnostic log",
            description = "Returns presigned download URL for a client log file (15-minute expiry)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Download URL generated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientLogDownloadResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Log or site not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping(ApiRoutes.ADMIN_SITES_CLIENT_LOGS_DOWNLOAD)
    public ResponseEntity<ClientLogDownloadResponseDto> downloadClientLog(
            @PathVariable("siteId") UUID siteId,
            @PathVariable("logId") UUID logId,
            Authentication authentication
    ) {
        logger.debug("Download client log: siteId={}, logId={}", siteId, logId);
        verifySiteAccess(siteId, authentication);

        ClientDiagnosticLog log = logService.getLog(siteId, logId);
        S3PresignedUrlService.PresignedUrlResult presignedUrl = logService.getDownloadUrl(siteId, logId);

        ClientLogDownloadResponseDto response = ClientLogDownloadResponseDto.fromPresignedUrl(presignedUrl, log);
        return ResponseEntity.ok(response);
    }

    /**
     * Verify that the authenticated user has access to the given site.
     * <p>
     * ROLE_ADMIN can access any site. ROLE_USER can only access sites
     * belonging to their own account.
     * </p>
     *
     * @param siteId         site identifier
     * @param authentication Spring Security authentication
     * @throws AccessDeniedException if ROLE_USER tries to access another account's site
     */
    private void verifySiteAccess(UUID siteId, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));

        if (isAdmin) {
            return;
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String accountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());
            if (accountIdStr == null || accountIdStr.isEmpty()) {
                accountIdStr = jwt.getClaimAsString("accountId");
            }
            if (accountIdStr != null && !accountIdStr.isEmpty()) {
                UUID accountId = UUID.fromString(accountIdStr);
                siteRepository.findByIdAndAccountId(siteId, accountId)
                        .orElseThrow(() -> new AccessDeniedException(
                                "You do not have access to this site's client logs"));
                return;
            }
        }

        throw new AccessDeniedException("Unable to verify site ownership");
    }
}
