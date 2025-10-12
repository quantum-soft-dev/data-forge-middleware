package com.bitbi.dfm.account.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for account statistics queries.
 * <p>
 * Provides aggregated statistics for admin dashboard.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional(readOnly = true)
public class AccountStatisticsService {

    private final SiteRepository siteRepository;
    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final ErrorLogRepository errorLogRepository;

    public AccountStatisticsService(
            SiteRepository siteRepository,
            BatchRepository batchRepository,
            UploadedFileRepository uploadedFileRepository,
            ErrorLogRepository errorLogRepository) {
        this.siteRepository = siteRepository;
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.errorLogRepository = errorLogRepository;
    }

    /**
     * Get statistics for specific account.
     * Optimized to use COUNT queries and ID-only queries to prevent N+1 queries.
     *
     * @param accountId account identifier
     * @return statistics map
     */
    public Map<String, Object> getAccountStatistics(UUID accountId) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("accountId", accountId);
        stats.put("totalSites", siteRepository.countByAccountId(accountId));
        // Optimized: Use countActiveByAccountId instead of loading all sites and calling .size()
        stats.put("activeSites", siteRepository.countActiveByAccountId(accountId));
        stats.put("totalBatches", batchRepository.countByAccountId(accountId));
        stats.put("activeBatches", batchRepository.countActiveBatchesByAccountId(accountId));
        stats.put("totalFiles", uploadedFileRepository.countByAccountId(accountId));
        // Optimized: Use findSiteIdsByAccountId to fetch only IDs instead of full Site entities
        stats.put("totalErrors", errorLogRepository.countBySiteIds(
                siteRepository.findSiteIdsByAccountId(accountId)
        ));

        return stats;
    }

    /**
     * Get global statistics across all accounts.
     *
     * @return global statistics map
     */
    public Map<String, Object> getGlobalStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("totalSites", siteRepository.count());
        stats.put("totalBatches", batchRepository.count());
        stats.put("activeBatches", batchRepository.countActiveBatches());
        stats.put("totalFiles", uploadedFileRepository.count());
        stats.put("totalErrors", errorLogRepository.count());

        return stats;
    }

    /**
     * Get statistics for site.
     *
     * @param siteId site identifier
     * @return site statistics map
     */
    public Map<String, Object> getSiteStatistics(UUID siteId) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("siteId", siteId);
        stats.put("totalBatches", batchRepository.countBySiteId(siteId));
        stats.put("activeBatch", batchRepository.findActiveBySiteId(siteId).isPresent());
        stats.put("totalFiles", uploadedFileRepository.countBySiteId(siteId));
        stats.put("totalErrors", errorLogRepository.countBySiteId(siteId));

        return stats;
    }

    /**
     * Get statistics for batch.
     *
     * @param batchId batch identifier
     * @return batch statistics map
     */
    public Map<String, Object> getBatchStatistics(UUID batchId) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("batchId", batchId);
        stats.put("totalFiles", uploadedFileRepository.countByBatchId(batchId));
        stats.put("totalErrors", errorLogRepository.countByBatchId(batchId));

        return stats;
    }
}
