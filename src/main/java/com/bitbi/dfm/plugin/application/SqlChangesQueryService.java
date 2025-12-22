package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
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
 * @see com.bitbi.dfm.plugin.presentation.BitBiPluginApiController
 */
@Service
public class SqlChangesQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlChangesQueryService.class);

    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final S3SqlFileStorageService s3SqlFileStorageService;
    private final SiteRepository siteRepository;
    private final MeterRegistry meterRegistry;

    public SqlChangesQueryService(
            PluginSqlGenerationRepository sqlGenerationRepository,
            S3SqlFileStorageService s3SqlFileStorageService,
            SiteRepository siteRepository,
            MeterRegistry meterRegistry) {
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.s3SqlFileStorageService = s3SqlFileStorageService;
        this.siteRepository = siteRepository;
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

            // Concatenate SQL content from S3
            StringBuilder sqlContent = new StringBuilder();
            for (PluginSqlGeneration generation : generations) {
                String content = s3SqlFileStorageService.getSqlFileContent(generation.getS3Key());
                sqlContent.append(content);
                if (!content.endsWith("\n")) {
                    sqlContent.append("\n");
                }
            }

            log.info("Retrieved {} SQL generation(s) for siteId={} since={}",
                    generations.size(), siteId, since);

            meterRegistry.counter("plugin.api.sql.changes.retrieved",
                    "siteId", siteId.toString(),
                    "count", String.valueOf(generations.size())).increment();

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
}
