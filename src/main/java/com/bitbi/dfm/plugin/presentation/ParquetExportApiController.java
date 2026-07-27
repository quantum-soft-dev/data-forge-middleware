package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.DownloadLinkService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileListing;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.domain.DownloadLink;
import com.bitbi.dfm.plugin.presentation.PluginApiKeyAuthenticationFilter.PluginApiKeyAuthenticationToken;
import com.bitbi.dfm.shared.api.ApiRoutes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Parquet Export plugin client API (028): Basic Auth file listing with registered one-time
 * download links.
 *
 * @see com.bitbi.dfm.plugin.presentation.ParquetExportBasicAuthFilter
 */
@RestController
@RequestMapping(ApiRoutes.PARQUET_EXPORT_PLUGIN_API)
@Tag(name = "Parquet Export Plugin API", description = "Basic Auth listing of Parquet files with one-time download links")
public class ParquetExportApiController {

    private static final Logger log = LoggerFactory.getLogger(ParquetExportApiController.class);
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final ParquetExportFileService fileService;
    private final DownloadLinkService downloadLinkService;
    private final PluginRateLimiterService rateLimiterService;
    private final PluginAuditService pluginAuditService;
    private final com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties properties;

    public ParquetExportApiController(ParquetExportFileService fileService,
                                      DownloadLinkService downloadLinkService,
                                      PluginRateLimiterService rateLimiterService,
                                      PluginAuditService pluginAuditService,
                                      com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties properties) {
        this.fileService = fileService;
        this.downloadLinkService = downloadLinkService;
        this.rateLimiterService = rateLimiterService;
        this.pluginAuditService = pluginAuditService;
        this.properties = properties;
    }

    /**
     * List Parquet files produced after {@code since}, registering a one-time download link
     * for every returned file.
     */
    @GetMapping("/files")
    @Operation(summary = "List Parquet files with one-time download links",
            description = "Basic Auth. Filters: since (ISO 8601, strictly greater), siteId, table, "
                    + "type (delta|checkpoint). Every listed file gets a freshly registered "
                    + "single-use download URL (TTL 1 hour by default).")
    public ResponseEntity<com.bitbi.dfm.plugin.presentation.dto.ParquetFileListResponseDto> listFiles(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        PluginApiKeyAuthenticationToken authentication =
                (PluginApiKeyAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        UUID accountId = authentication.getAccountId();
        Long accountPluginId = authentication.getAccountPluginId();

        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .build();
        }

        if (page < 0 || size < 1 || size > ParquetExportFileService.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid pagination: page >= 0, 1 <= size <= "
                    + ParquetExportFileService.MAX_PAGE_SIZE);
        }
        LocalDateTime sinceValue = parseSince(since);
        FileType typeValue = parseType(type);

        FileListing listing = fileService.listFiles(accountId, sinceValue, siteId, table, typeValue, page, size);
        List<DownloadLink> links = downloadLinkService.registerLinks(accountPluginId, listing.files());

        String urlPrefix = downloadUrlPrefix();
        List<com.bitbi.dfm.plugin.presentation.dto.ParquetFileResponseDto> files =
                new ArrayList<>(listing.files().size());
        for (int i = 0; i < listing.files().size(); i++) {
            ParquetFileItem item = listing.files().get(i);
            DownloadLink link = links.get(i);
            files.add(com.bitbi.dfm.plugin.presentation.dto.ParquetFileResponseDto.of(
                    item, link, urlPrefix + link.getToken()));
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("since", sinceValue.toString());
        if (siteId != null) filters.put("siteId", siteId.toString());
        if (table != null) filters.put("table", table);
        if (typeValue != null) filters.put("type", typeValue.name());
        filters.put("page", page);
        filters.put("size", size);
        pluginAuditService.logFilesListed(com.bitbi.dfm.plugin.application.ParquetExportCredentialsService.PLUGIN_ID,
                accountId, filters, files.size());

        log.debug("parquet-export listing served: accountId={}, files={}", accountId, files.size());
        return ResponseEntity.ok(new com.bitbi.dfm.plugin.presentation.dto.ParquetFileListResponseDto(
                files, listing.page(), listing.size(), listing.hasMore()));
    }

    private LocalDateTime parseSince(String since) {
        if (since == null || since.isBlank()) {
            return EPOCH;
        }
        try {
            return LocalDateTime.parse(since);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid 'since' value, expected ISO 8601 datetime (e.g. 2026-07-27T00:00:00)");
        }
    }

    private FileType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return FileType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid 'type' value, expected delta or checkpoint");
        }
    }

    private String downloadUrlPrefix() {
        String downloadPath = ApiRoutes.PARQUET_EXPORT_PLUGIN_API + "/download/";
        String baseUrl = properties.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.replaceAll("/+$", "") + downloadPath;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + downloadPath;
    }
}
