package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.CsvFileQueryService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.application.SqlChangesQueryService;
import com.bitbi.dfm.plugin.presentation.dto.FileDto;
import com.bitbi.dfm.plugin.presentation.dto.FileListResponseDto;
import com.bitbi.dfm.plugin.presentation.dto.SiteDto;
import com.bitbi.dfm.plugin.presentation.dto.SiteListResponseDto;
import com.bitbi.dfm.plugin.presentation.dto.TableDto;
import com.bitbi.dfm.plugin.presentation.dto.TableListResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.site.domain.Site;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for the Bit BI Plugin API.
 *
 * <p>This API provides endpoints for Bit BI to retrieve SQL changes generated
 * from batch upload comparisons. Authentication is via Plugin API Key in the
 * X-Plugin-Api-Key header.</p>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /sql-changes - Retrieve SQL changes for a site since a given date</li>
 *   <li>GET /sites - List available sites for the account</li>
 *   <li>GET /sites/{siteId}/files - List CSV files for a site</li>
 *   <li>GET /sites/{siteId}/files/{fileName} - Download a CSV file</li>
 * </ul>
 *
 * @see PluginApiKeyAuthenticationFilter
 * @see SqlChangesQueryService
 */
@RestController
@RequestMapping(ApiRoutes.BITBI_PLUGIN_API)
@Tag(name = "Bit BI Plugin API", description = "Plugin API for Bit BI integration")
@SecurityRequirement(name = "PluginApiKey")
public class BitBiPluginApiController {

    private static final Logger log = LoggerFactory.getLogger(BitBiPluginApiController.class);

    private final SqlChangesQueryService sqlChangesQueryService;
    private final CsvFileQueryService csvFileQueryService;
    private final PluginRateLimiterService rateLimiterService;

    public BitBiPluginApiController(
            SqlChangesQueryService sqlChangesQueryService,
            CsvFileQueryService csvFileQueryService,
            PluginRateLimiterService rateLimiterService) {
        this.sqlChangesQueryService = sqlChangesQueryService;
        this.csvFileQueryService = csvFileQueryService;
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Retrieves SQL changes for a site since a given date.
     *
     * <p>Returns concatenated SQL statements (INSERT, UPDATE, DELETE) generated
     * from batch comparisons for the specified site after the given timestamp.</p>
     *
     * @param siteId the UUID of the site to retrieve changes for
     * @param since ISO8601 timestamp to retrieve changes after
     * @param authentication the Plugin API Key authentication context
     * @return SQL statements as plain text, or empty body if no changes
     */
    @GetMapping(value = "/sql-changes", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(
        summary = "Retrieve SQL changes for a site",
        description = "Returns all SQL changes (INSERT, UPDATE, DELETE statements) for a specific site after a given date"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SQL changes retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
        @ApiResponse(responseCode = "403", description = "Site does not belong to account"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<String> getSqlChanges(
            @Parameter(description = "UUID of the site to retrieve changes for", required = true)
            @RequestParam UUID siteId,
            @Parameter(description = "ISO8601 timestamp. Returns changes after this date.", required = true)
            @RequestParam String since,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);

        // Check rate limit
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body("Rate limit exceeded. Please retry after " + retryAfter + " seconds.");
        }

        // Set MDC context for structured logging
        MDC.put("accountId", accountId.toString());
        MDC.put("siteId", siteId.toString());

        try {
            log.debug("Getting SQL changes for siteId={}, since={}, accountId={}", siteId, since, accountId);

            // Parse the since timestamp
            Instant sinceInstant;
            try {
                sinceInstant = Instant.parse(since);
            } catch (DateTimeParseException e) {
                log.warn("Invalid date format for 'since' parameter: {}", since);
                throw new IllegalArgumentException("Invalid date format for 'since' parameter. Expected ISO8601 format (e.g., 2025-01-01T00:00:00Z)");
            }

            String sqlContent = sqlChangesQueryService.getSqlChanges(accountId, siteId, sinceInstant);
            return ResponseEntity.ok(sqlContent);

        } catch (SecurityException e) {
            log.warn("Site access denied: siteId={}, accountId={}, message={}", siteId, accountId, e.getMessage());
            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Lists all available sites for the account.
     *
     * <p>Returns all active sites belonging to the account associated with
     * the API key.</p>
     *
     * @param authentication the Plugin API Key authentication context
     * @return list of sites with their IDs, domains, and display names
     */
    @GetMapping(value = "/sites", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "List available sites",
        description = "Returns a list of all sites belonging to the account associated with the API key"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Sites retrieved successfully",
            content = @Content(schema = @Schema(implementation = SiteListResponseDto.class))
        ),
        @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<SiteListResponseDto> listSites(Authentication authentication) {

        UUID accountId = extractAccountId(authentication);

        // Check rate limit
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }

        // Set MDC context for structured logging
        MDC.put("accountId", accountId.toString());

        try {
            log.debug("Listing sites for accountId={}", accountId);

            List<Site> sites = sqlChangesQueryService.listSites(accountId);

            List<SiteDto> siteDtos = sites.stream()
                    .map(site -> new SiteDto(
                            site.getId(),
                            site.getDomain(),
                            site.getDisplayName()))
                    .toList();

            return ResponseEntity.ok(new SiteListResponseDto(siteDtos));
        } finally {
            MDC.clear();
        }
    }

    /**
     * Lists all unique tables (uploaded files) for the account.
     *
     * <p>Returns unique table names derived from original file names,
     * along with the file size and upload timestamp of the most recent upload for each.</p>
     *
     * @param authentication the Plugin API Key authentication context
     * @return list of tables with their latest upload info
     */
    @GetMapping(value = "/tables", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "List available tables",
        description = "Returns a list of all unique tables (uploaded files) for the account, with latest upload info"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Tables retrieved successfully",
            content = @Content(schema = @Schema(implementation = TableListResponseDto.class))
        ),
        @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<TableListResponseDto> listTables(Authentication authentication) {

        UUID accountId = extractAccountId(authentication);

        // Check rate limit
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }

        // Set MDC context for structured logging
        MDC.put("accountId", accountId.toString());

        try {
            log.debug("Listing tables for accountId={}", accountId);

            List<TableDto> tables = sqlChangesQueryService.listTables(accountId);
            return ResponseEntity.ok(new TableListResponseDto(tables));
        } finally {
            MDC.clear();
        }
    }

    /**
     * Lists all CSV files for a site.
     *
     * <p>Returns the latest version of each CSV file available for download.
     * Use this endpoint for plugin initialization to download baseline CSV files
     * instead of SQL statements.</p>
     *
     * @param siteId the UUID of the site to list files for
     * @param authentication the Plugin API Key authentication context
     * @return list of files with their metadata
     */
    @GetMapping(value = "/sites/{siteId}/files", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "List CSV files for a site",
        description = "Returns a list of all CSV files available for the site. " +
                "Use this for plugin initialization to download baseline data."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Files retrieved successfully",
            content = @Content(schema = @Schema(implementation = FileListResponseDto.class))
        ),
        @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
        @ApiResponse(responseCode = "403", description = "Site does not belong to account"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<FileListResponseDto> listFiles(
            @Parameter(description = "UUID of the site", required = true)
            @PathVariable UUID siteId,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);

        // Check rate limit
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }

        // Set MDC context for structured logging
        MDC.put("accountId", accountId.toString());
        MDC.put("siteId", siteId.toString());

        try {
            log.debug("Listing files for siteId={}, accountId={}", siteId, accountId);

            List<FileDto> files = csvFileQueryService.listFiles(accountId, siteId);
            return ResponseEntity.ok(FileListResponseDto.of(files));

        } catch (SecurityException e) {
            log.warn("Site access denied: siteId={}, accountId={}, message={}", siteId, accountId, e.getMessage());
            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Downloads a CSV file.
     *
     * <p>Returns the file content directly from S3, streaming through the server.
     * Files are returned in their original compressed format (.csv.gz).</p>
     *
     * @param siteId the UUID of the site the file belongs to
     * @param fileName the name of the file to download (e.g., "customers.csv.gz")
     * @param authentication the Plugin API Key authentication context
     * @return the file content as a stream
     */
    @GetMapping(value = "/sites/{siteId}/files/{fileName}")
    @Operation(
        summary = "Download a CSV file",
        description = "Downloads the specified CSV file from S3. " +
                "Files are returned in their original format (compressed .csv.gz)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "File downloaded successfully"),
        @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
        @ApiResponse(responseCode = "403", description = "Site does not belong to account"),
        @ApiResponse(responseCode = "404", description = "File not found"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
        @ApiResponse(responseCode = "500", description = "Error downloading file from S3")
    })
    public ResponseEntity<InputStreamResource> downloadFile(
            @Parameter(description = "UUID of the site", required = true)
            @PathVariable UUID siteId,
            @Parameter(description = "Name of the file to download (e.g., customers.csv.gz)", required = true)
            @PathVariable String fileName,
            Authentication authentication) {

        UUID accountId = extractAccountId(authentication);

        // Check rate limit
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }

        // Set MDC context for structured logging
        MDC.put("accountId", accountId.toString());
        MDC.put("siteId", siteId.toString());
        MDC.put("fileName", fileName);

        try {
            log.debug("Downloading file: siteId={}, fileName={}, accountId={}", siteId, fileName, accountId);

            CsvFileQueryService.FileDownloadResult result = csvFileQueryService.downloadFile(accountId, siteId, fileName);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.fileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(result.fileSize()))
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    .body(new InputStreamResource(result.inputStream()));

        } catch (SecurityException e) {
            log.warn("Site access denied: siteId={}, accountId={}, message={}", siteId, accountId, e.getMessage());
            throw e;
        } catch (CsvFileQueryService.FileNotFoundException e) {
            log.warn("File not found: siteId={}, fileName={}", siteId, fileName);
            throw e;
        } catch (CsvFileQueryService.FileDownloadException e) {
            log.error("File download failed: siteId={}, fileName={}, error={}", siteId, fileName, e.getMessage());
            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Extracts the account ID from the authentication context.
     */
    private UUID extractAccountId(Authentication authentication) {
        if (authentication instanceof PluginApiKeyAuthenticationFilter.PluginApiKeyAuthenticationToken token) {
            return token.getAccountId();
        }
        throw new IllegalStateException("Expected PluginApiKeyAuthenticationToken but got: " + authentication.getClass().getName());
    }
}
