package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.plugin.presentation.dto.TableDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Service for querying and retrieving SQL changes for the Bit BI Plugin API.
 *
 * <p>Provides functionality to:
 * <ul>
 *   <li>Retrieve concatenated SQL changes for a site since a given timestamp</li>
 *   <li>List available sites for an account</li>
 *   <li>Validate site ownership</li>
 * </ul>
 *
 * <p><strong>Pagination:</strong> To prevent OOM with large result sets,
 * results are limited to {@link #MAX_SQL_GENERATIONS} records. Clients
 * should use appropriate 'since' timestamps to fetch data incrementally.</p>
 *
 * @see com.bitbi.dfm.plugin.presentation.BitBiPluginApiController
 */
@Service
public class SqlChangesQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlChangesQueryService.class);

    /**
     * Maximum number of SQL generation records to return in a single request.
     * Prevents OOM when concatenating large numbers of SQL files.
     * If more records exist, a warning comment is prepended to the response.
     */
    private static final int MAX_SQL_GENERATIONS = 100;

    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final S3SqlFileStorageService s3SqlFileStorageService;
    private final SiteRepository siteRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final MeterRegistry meterRegistry;

    public SqlChangesQueryService(
            PluginSqlGenerationRepository sqlGenerationRepository,
            S3SqlFileStorageService s3SqlFileStorageService,
            SiteRepository siteRepository,
            UploadedFileRepository uploadedFileRepository,
            MeterRegistry meterRegistry) {
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.s3SqlFileStorageService = s3SqlFileStorageService;
        this.siteRepository = siteRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Retrieves concatenated SQL changes for a site since a given timestamp.
     *
     * <p>Validates that the site belongs to the account, then fetches all
     * SQL generation records for the site since the specified time, retrieves
     * their content from S3, and concatenates them in chronological order.</p>
     *
     * @param accountId the account ID from the API key authentication
     * @param siteId the site ID to retrieve changes for
     * @param since the timestamp to retrieve changes after
     * @return concatenated SQL statements, or empty string if no changes
     * @throws SecurityException if the site does not belong to the account
     */
    @Transactional(readOnly = true)
    public String getSqlChanges(UUID accountId, UUID siteId, Instant since) {
        Timer.Sample timer = Timer.start(meterRegistry);

        try {
            // Validate site ownership
            Site site = siteRepository.findById(siteId)
                    .orElseThrow(() -> new SecurityException("Site does not belong to your account"));

            if (!site.getAccountId().equals(accountId)) {
                log.warn("Site ownership mismatch: siteId={}, requestedAccountId={}, actualAccountId={}",
                        siteId, accountId, site.getAccountId());
                throw new SecurityException("Site does not belong to your account");
            }

            // Fetch SQL generation records - convert Instant to LocalDateTime for query
            LocalDateTime sinceDateTime = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
            List<PluginSqlGeneration> generations = sqlGenerationRepository
                    .findBySiteIdAndCreatedAtAfter(siteId, sinceDateTime);

            if (generations.isEmpty()) {
                log.debug("No SQL changes found for siteId={} since={}", siteId, since);
                return "";
            }

            // Check if results exceed limit
            boolean truncated = generations.size() > MAX_SQL_GENERATIONS;
            if (truncated) {
                log.warn("SQL changes result truncated: total={}, limit={}, siteId={}",
                        generations.size(), MAX_SQL_GENERATIONS, siteId);
                generations = generations.subList(0, MAX_SQL_GENERATIONS);
            }

            // Concatenate SQL content from S3
            StringBuilder sqlContent = new StringBuilder();

            // Add truncation warning if needed
            if (truncated) {
                sqlContent.append("-- WARNING: Results truncated. ")
                        .append("Only first ").append(MAX_SQL_GENERATIONS)
                        .append(" batches returned. Use a more recent 'since' parameter.\n\n");
            }

            for (PluginSqlGeneration generation : generations) {
                String content = s3SqlFileStorageService.getSqlFileContent(generation.getS3Key());
                sqlContent.append(content);
                if (!content.endsWith("\n")) {
                    sqlContent.append("\n");
                }
            }

            log.info("Retrieved {} SQL generation(s) for siteId={} since={}, truncated={}",
                    generations.size(), siteId, since, truncated);

            meterRegistry.counter("plugin.api.sql.changes.retrieved",
                    "siteId", siteId.toString(),
                    "count", String.valueOf(generations.size()),
                    "truncated", String.valueOf(truncated)).increment();

            return sqlContent.toString();

        } finally {
            timer.stop(meterRegistry.timer("plugin.api.sql.changes.duration"));
        }
    }

    /**
     * Lists all active sites for an account.
     *
     * @param accountId the account ID from the API key authentication
     * @return list of active sites belonging to the account
     */
    @Transactional(readOnly = true)
    public List<Site> listSites(UUID accountId) {
        log.debug("Listing sites for accountId={}", accountId);
        return siteRepository.findActiveByAccountId(accountId);
    }

    /**
     * Lists all unique tables (uploaded files) for an account with their latest upload info.
     *
     * <p>Returns a unique list of table names derived from original file names,
     * along with the file size and upload timestamp of the most recent upload for each.</p>
     *
     * @param accountId the account ID from the API key authentication
     * @return list of table DTOs with latest upload info
     */
    @Transactional(readOnly = true)
    public List<TableDto> listTables(UUID accountId) {
        log.debug("Listing tables for accountId={}", accountId);

        List<UploadedFileRepository.LatestFileInfo> latestFiles =
                uploadedFileRepository.findLatestByOriginalFileNameForAccount(accountId);

        return latestFiles.stream()
                .map(file -> TableDto.of(
                        TableDto.deriveTableName(file.getOriginalFileName()),
                        file.getFileSize(),
                        file.getUploadedAt()))
                .toList();
    }
}
