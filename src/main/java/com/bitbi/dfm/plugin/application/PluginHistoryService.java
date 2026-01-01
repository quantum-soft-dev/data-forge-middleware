package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.plugin.presentation.dto.*;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for plugin history management operations.
 * Provides view, clear, and regenerate functionality for SQL generation history.
 *
 * <p>Feature 014: Plugin History Management</p>
 */
@Service
public class PluginHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PluginHistoryService.class);

    /**
     * SQL statement delimiter used by the Bit BI plugin SQL generator.
     *
     * <p><strong>Contract:</strong> The SQL generation process (SqlGenerationService) uses this
     * delimiter to separate individual SQL statements in the generated file. This delimiter
     * is designed to be unique and unlikely to appear in normal SQL content:</p>
     *
     * <ul>
     *   <li>Format: {@code --- END OF COMMAND ---}</li>
     *   <li>The generator inserts this delimiter after each complete SQL statement</li>
     *   <li>The delimiter should NOT appear within SQL string literals or comments in generated SQL</li>
     *   <li>If the source CSV data contains this pattern, it will be escaped during generation</li>
     * </ul>
     *
     * @see com.bitbi.dfm.plugin.application.SqlGenerationService#generateSql
     */
    private static final String STATEMENT_DELIMITER = "--- END OF COMMAND ---";
    private static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * Maximum file size allowed for S3 content reads (50 MB).
     * Prevents memory exhaustion from extremely large SQL files.
     */
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final SiteRepository siteRepository;
    private final BatchRepository batchRepository;
    private final S3SqlFileStorageService s3StorageService;
    private final PluginAuditService auditService;
    private final SqlGenerationService sqlGenerationService;

    public PluginHistoryService(
            PluginSqlGenerationRepository sqlGenerationRepository,
            AccountPluginRepository accountPluginRepository,
            SiteRepository siteRepository,
            BatchRepository batchRepository,
            S3SqlFileStorageService s3StorageService,
            PluginAuditService auditService,
            SqlGenerationService sqlGenerationService
    ) {
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.siteRepository = siteRepository;
        this.batchRepository = batchRepository;
        this.s3StorageService = s3StorageService;
        this.auditService = auditService;
        this.sqlGenerationService = sqlGenerationService;
    }

    // ==================== User Story 1: View History ====================

    /**
     * Lists SQL generations for an account-plugin with pagination.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param includeSuperseded whether to include superseded generations
     * @param pageable pagination parameters
     * @return page of SQL generation summaries
     */
    @Transactional(readOnly = true)
    public Page<SqlGenerationSummaryDto> listGenerations(
            String pluginId,
            UUID accountId,
            boolean includeSuperseded,
            Pageable pageable
    ) {
        log.debug("Listing generations: pluginId={}, accountId={}, includeSuperseded={}",
                pluginId, accountId, includeSuperseded);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);

        Page<PluginSqlGeneration> generations = sqlGenerationRepository
                .findByAccountPluginId(accountPlugin.getId(), includeSuperseded, pageable);

        // Get all unique site IDs for batch lookup
        Set<UUID> siteIds = generations.getContent().stream()
                .map(PluginSqlGeneration::getSiteId)
                .collect(Collectors.toSet());

        // Batch load sites
        Map<UUID, String> siteDomains = loadSiteDomains(siteIds);

        // Map to DTOs
        List<SqlGenerationSummaryDto> dtos = generations.getContent().stream()
                .map(gen -> SqlGenerationSummaryDto.fromEntity(gen, siteDomains.getOrDefault(gen.getSiteId(), "unknown")))
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, generations.getTotalElements());
    }

    /**
     * Gets a single SQL generation by ID.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the generation ID
     * @return the SQL generation summary
     */
    @Transactional(readOnly = true)
    public SqlGenerationSummaryDto getGeneration(String pluginId, UUID accountId, UUID generationId) {
        log.debug("Getting generation: pluginId={}, accountId={}, generationId={}",
                pluginId, accountId, generationId);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);
        PluginSqlGeneration generation = findGeneration(generationId, accountPlugin.getId());

        String siteDomain = siteRepository.findById(generation.getSiteId())
                .map(Site::getDomain)
                .orElse("unknown");

        return SqlGenerationSummaryDto.fromEntity(generation, siteDomain);
    }

    /**
     * Gets paginated SQL content for a generation.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the generation ID
     * @param page page number (0-based)
     * @param pageSize statements per page
     * @return paginated SQL content
     */
    @Transactional(readOnly = true)
    public SqlContentPageDto getSqlContent(
            String pluginId,
            UUID accountId,
            UUID generationId,
            int page,
            int pageSize
    ) {
        log.debug("Getting SQL content: pluginId={}, accountId={}, generationId={}, page={}",
                pluginId, accountId, generationId, page);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);
        PluginSqlGeneration generation = findGeneration(generationId, accountPlugin.getId());

        // Check file size before loading into memory
        validateFileSize(generation);

        // Fetch SQL content from S3
        String sqlContent = s3StorageService.getSqlFileContent(generation.getS3Key());

        // Parse statements
        List<String> statements = parseStatements(sqlContent);

        // Ensure valid page size
        int effectivePageSize = pageSize > 0 ? Math.min(pageSize, DEFAULT_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        return SqlContentPageDto.create(generationId, statements, page, effectivePageSize);
    }

    /**
     * Downloads the complete SQL file content.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the generation ID
     * @return the SQL file content
     */
    @Transactional(readOnly = true)
    public String downloadSqlFile(String pluginId, UUID accountId, UUID generationId) {
        log.debug("Downloading SQL file: pluginId={}, accountId={}, generationId={}",
                pluginId, accountId, generationId);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);
        PluginSqlGeneration generation = findGeneration(generationId, accountPlugin.getId());

        // Check file size before loading into memory
        validateFileSize(generation);

        return s3StorageService.getSqlFileContent(generation.getS3Key());
    }

    // ==================== User Story 2: Clear History ====================

    /**
     * Gets summary of what will be deleted when clearing history.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @return summary of what will be deleted
     */
    @Transactional(readOnly = true)
    public HistoryClearSummaryDto getHistorySummary(String pluginId, UUID accountId) {
        log.debug("Getting history summary: pluginId={}, accountId={}", pluginId, accountId);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);

        Object[] countAndSum = sqlGenerationRepository.countAndSumByAccountPluginId(accountPlugin.getId());
        long count = ((Number) countAndSum[0]).longValue();
        long totalBytes = ((Number) countAndSum[1]).longValue();

        // Check for active batches across account's sites
        boolean hasActiveBatches = checkForActiveBatches(accountId);

        return HistoryClearSummaryDto.create(accountId, pluginId, count, totalBytes, hasActiveBatches);
    }

    /**
     * Clears all plugin history for an account.
     * Deletes S3 files, database records, and deactivates the plugin.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @return result of the clear operation
     */
    @Transactional
    public HistoryClearResultDto clearHistory(String pluginId, UUID accountId) {
        log.info("Clearing history: pluginId={}, accountId={}", pluginId, accountId);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);
        Long accountPluginId = accountPlugin.getId();

        // Get statistics before deletion
        Object[] countAndSum = sqlGenerationRepository.countAndSumByAccountPluginId(accountPluginId);
        long count = ((Number) countAndSum[0]).longValue();
        long totalBytes = ((Number) countAndSum[1]).longValue();

        // Get all S3 keys for deletion
        List<String> s3Keys = sqlGenerationRepository.findS3KeysByAccountPluginId(accountPluginId);

        // Delete S3 files (best effort, collect failures)
        List<String> failedKeys = deleteS3Files(s3Keys);

        // Delete database records
        sqlGenerationRepository.deleteByAccountPluginId(accountPluginId);

        // Deactivate plugin
        accountPlugin.deactivate();
        accountPluginRepository.save(accountPlugin);

        // Audit log
        auditService.logHistoryCleared(pluginId, accountId, count, s3Keys.size(), totalBytes);

        log.info("History cleared: pluginId={}, accountId={}, deleted={}, failedS3={}",
                pluginId, accountId, count, failedKeys.size());

        return HistoryClearResultDto.create(count, s3Keys.size() - failedKeys.size(), totalBytes, failedKeys);
    }

    // ==================== User Story 3: Regenerate ====================

    /**
     * Regenerates SQL for a batch.
     * Marks the original generation as superseded and creates a new one.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the generation ID to regenerate
     * @return result of the regeneration
     */
    @Transactional
    public RegenerateResultDto regenerateSql(String pluginId, UUID accountId, UUID generationId) {
        log.info("Regenerating SQL: pluginId={}, accountId={}, generationId={}",
                pluginId, accountId, generationId);

        AccountPlugin accountPlugin = findAccountPlugin(accountId, pluginId);
        PluginSqlGeneration original = findGeneration(generationId, accountPlugin.getId());

        // Validate not already superseded
        if (original.isSuperseded()) {
            throw new IllegalStateException("Generation is already superseded: " + generationId);
        }

        // Get the batch ID from the original generation
        UUID batchId = original.getSourceBatchId();

        // Generate new SQL
        PluginSqlGeneration newGeneration = sqlGenerationService.regenerateForBatch(
                batchId, accountPlugin.getId());

        // Mark original as superseded
        original.markAsSuperseded(newGeneration.getId());
        sqlGenerationRepository.save(original);

        log.info("Regeneration completed: original={}, new={}",
                generationId, newGeneration.getId());

        return RegenerateResultDto.fromEntity(original.getId(), newGeneration);
    }

    // ==================== Helper Methods ====================

    private AccountPlugin findAccountPlugin(UUID accountId, String pluginId) {
        return accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No plugin activation found: accountId=" + accountId + ", pluginId=" + pluginId));
    }

    private PluginSqlGeneration findGeneration(UUID generationId, Long accountPluginId) {
        PluginSqlGeneration generation = sqlGenerationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalArgumentException("Generation not found: " + generationId));

        // Verify ownership
        if (!generation.getAccountPluginId().equals(accountPluginId)) {
            throw new IllegalArgumentException("Generation does not belong to account-plugin: " + generationId);
        }

        return generation;
    }

    private Map<UUID, String> loadSiteDomains(Set<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return Map.of();
        }
        // Batch load to prevent N+1 queries
        return siteRepository.findAllById(siteIds).stream()
                .collect(Collectors.toMap(Site::getId, Site::getDomain));
    }

    private boolean checkForActiveBatches(UUID accountId) {
        // Get all site IDs for the account
        List<UUID> siteIds = siteRepository.findSiteIdsByAccountId(accountId);
        if (siteIds.isEmpty()) {
            return false;
        }

        // Check each site for active batches
        for (UUID siteId : siteIds) {
            if (batchRepository.findActiveBySiteId(siteId).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseStatements(String sqlContent) {
        if (sqlContent == null || sqlContent.isBlank()) {
            return List.of();
        }

        // Simple split by delimiter - no regex needed
        String[] parts = sqlContent.split(STATEMENT_DELIMITER);
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private void validateFileSize(PluginSqlGeneration generation) {
        Long fileSize = generation.getFileSizeBytes();
        if (fileSize != null && fileSize > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File size exceeds maximum allowed: " + fileSize + " bytes (max: " + MAX_FILE_SIZE_BYTES + " bytes)");
        }
    }

    private List<String> deleteS3Files(List<String> s3Keys) {
        List<String> failedKeys = new ArrayList<>();

        for (String key : s3Keys) {
            try {
                s3StorageService.deleteFile(key);
            } catch (Exception e) {
                log.warn("Failed to delete S3 file: key={}, error={}", key, e.getMessage());
                failedKeys.add(key);
            }
        }

        return failedKeys;
    }
}
