package com.bitbi.dfm.account.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountStatisticsService.
 */
@DisplayName("AccountStatisticsService Unit Tests")
class AccountStatisticsServiceTest {

    private AccountStatisticsService statisticsService;
    private SiteRepository siteRepository;
    private BatchRepository batchRepository;
    private UploadedFileRepository uploadedFileRepository;
    private ErrorLogRepository errorLogRepository;

    @BeforeEach
    void setUp() {
        siteRepository = mock(SiteRepository.class);
        batchRepository = mock(BatchRepository.class);
        uploadedFileRepository = mock(UploadedFileRepository.class);
        errorLogRepository = mock(ErrorLogRepository.class);

        statisticsService = new AccountStatisticsService(
                siteRepository,
                batchRepository,
                uploadedFileRepository,
                errorLogRepository
        );
    }

    @Test
    @DisplayName("Should get account statistics successfully")
    void shouldGetAccountStatisticsSuccessfully() {
        // Given
        UUID accountId = UUID.randomUUID();
        UUID site1Id = UUID.randomUUID();
        UUID site2Id = UUID.randomUUID();

        Site site1 = Site.createForTesting(accountId, "site1.example.com", "Site 1");
        Site site2 = Site.createForTesting(accountId, "site2.example.com", "Site 2");
        List<Site> sites = Arrays.asList(site1, site2);
        List<UUID> siteIds = Arrays.asList(site1Id, site2Id);

        when(siteRepository.countByAccountId(accountId)).thenReturn(5L);
        when(siteRepository.findActiveByAccountId(accountId)).thenReturn(Arrays.asList(site1, site2));
        when(batchRepository.countByAccountId(accountId)).thenReturn(10L);
        when(batchRepository.countActiveBatchesByAccountId(accountId)).thenReturn(3);
        when(uploadedFileRepository.countByAccountId(accountId)).thenReturn(25L);
        when(siteRepository.findByAccountId(accountId)).thenReturn(sites);
        when(errorLogRepository.countBySiteIds(anyList())).thenReturn(15L);

        // When
        Map<String, Object> stats = statisticsService.getAccountStatistics(accountId);

        // Then
        assertNotNull(stats);
        assertEquals(accountId, stats.get("accountId"));
        assertEquals(5L, stats.get("totalSites"));
        assertEquals(2, stats.get("activeSites"));
        assertEquals(10L, stats.get("totalBatches"));
        assertEquals(3, stats.get("activeBatches"));
        assertEquals(25L, stats.get("totalFiles"));
        assertEquals(15L, stats.get("totalErrors"));

        verify(siteRepository).countByAccountId(accountId);
        verify(siteRepository).findActiveByAccountId(accountId);
        verify(batchRepository).countByAccountId(accountId);
        verify(batchRepository).countActiveBatchesByAccountId(accountId);
        verify(uploadedFileRepository).countByAccountId(accountId);
        verify(siteRepository).findByAccountId(accountId);
        verify(errorLogRepository).countBySiteIds(anyList());
    }

    @Test
    @DisplayName("Should get account statistics with zero counts")
    void shouldGetAccountStatisticsWithZeroCounts() {
        // Given
        UUID accountId = UUID.randomUUID();

        when(siteRepository.countByAccountId(accountId)).thenReturn(0L);
        when(siteRepository.findActiveByAccountId(accountId)).thenReturn(Arrays.asList());
        when(batchRepository.countByAccountId(accountId)).thenReturn(0L);
        when(batchRepository.countActiveBatchesByAccountId(accountId)).thenReturn(0);
        when(uploadedFileRepository.countByAccountId(accountId)).thenReturn(0L);
        when(siteRepository.findByAccountId(accountId)).thenReturn(Arrays.asList());
        when(errorLogRepository.countBySiteIds(anyList())).thenReturn(0L);

        // When
        Map<String, Object> stats = statisticsService.getAccountStatistics(accountId);

        // Then
        assertNotNull(stats);
        assertEquals(0L, stats.get("totalSites"));
        assertEquals(0, stats.get("activeSites"));
        assertEquals(0L, stats.get("totalBatches"));
        assertEquals(0, stats.get("activeBatches"));
        assertEquals(0L, stats.get("totalFiles"));
        assertEquals(0L, stats.get("totalErrors"));
    }

    @Test
    @DisplayName("Should get global statistics successfully")
    void shouldGetGlobalStatisticsSuccessfully() {
        // Given
        when(siteRepository.count()).thenReturn(100L);
        when(batchRepository.count()).thenReturn(500L);
        when(batchRepository.countActiveBatches()).thenReturn(50L);
        when(uploadedFileRepository.count()).thenReturn(2500L);
        when(errorLogRepository.count()).thenReturn(150L);

        // When
        Map<String, Object> stats = statisticsService.getGlobalStatistics();

        // Then
        assertNotNull(stats);
        assertEquals(100L, stats.get("totalSites"));
        assertEquals(500L, stats.get("totalBatches"));
        assertEquals(50L, stats.get("activeBatches"));
        assertEquals(2500L, stats.get("totalFiles"));
        assertEquals(150L, stats.get("totalErrors"));

        verify(siteRepository).count();
        verify(batchRepository).count();
        verify(batchRepository).countActiveBatches();
        verify(uploadedFileRepository).count();
        verify(errorLogRepository).count();
    }

    @Test
    @DisplayName("Should get global statistics with zero counts")
    void shouldGetGlobalStatisticsWithZeroCounts() {
        // Given
        when(siteRepository.count()).thenReturn(0L);
        when(batchRepository.count()).thenReturn(0L);
        when(batchRepository.countActiveBatches()).thenReturn(0L);
        when(uploadedFileRepository.count()).thenReturn(0L);
        when(errorLogRepository.count()).thenReturn(0L);

        // When
        Map<String, Object> stats = statisticsService.getGlobalStatistics();

        // Then
        assertNotNull(stats);
        assertEquals(0L, stats.get("totalSites"));
        assertEquals(0L, stats.get("totalBatches"));
        assertEquals(0L, stats.get("activeBatches"));
        assertEquals(0L, stats.get("totalFiles"));
        assertEquals(0L, stats.get("totalErrors"));
    }

    @Test
    @DisplayName("Should get site statistics successfully")
    void shouldGetSiteStatisticsSuccessfully() {
        // Given
        UUID siteId = UUID.randomUUID();

        when(batchRepository.countBySiteId(siteId)).thenReturn(15L);
        when(batchRepository.findActiveBySiteId(siteId)).thenReturn(Optional.of(mock(com.bitbi.dfm.batch.domain.Batch.class)));
        when(uploadedFileRepository.countBySiteId(siteId)).thenReturn(75L);
        when(errorLogRepository.countBySiteId(siteId)).thenReturn(10L);

        // When
        Map<String, Object> stats = statisticsService.getSiteStatistics(siteId);

        // Then
        assertNotNull(stats);
        assertEquals(siteId, stats.get("siteId"));
        assertEquals(15L, stats.get("totalBatches"));
        assertEquals(true, stats.get("activeBatch"));
        assertEquals(75L, stats.get("totalFiles"));
        assertEquals(10L, stats.get("totalErrors"));

        verify(batchRepository).countBySiteId(siteId);
        verify(batchRepository).findActiveBySiteId(siteId);
        verify(uploadedFileRepository).countBySiteId(siteId);
        verify(errorLogRepository).countBySiteId(siteId);
    }

    @Test
    @DisplayName("Should get site statistics when no active batch")
    void shouldGetSiteStatisticsWhenNoActiveBatch() {
        // Given
        UUID siteId = UUID.randomUUID();

        when(batchRepository.countBySiteId(siteId)).thenReturn(5L);
        when(batchRepository.findActiveBySiteId(siteId)).thenReturn(Optional.empty());
        when(uploadedFileRepository.countBySiteId(siteId)).thenReturn(25L);
        when(errorLogRepository.countBySiteId(siteId)).thenReturn(3L);

        // When
        Map<String, Object> stats = statisticsService.getSiteStatistics(siteId);

        // Then
        assertNotNull(stats);
        assertEquals(false, stats.get("activeBatch"));
    }

    @Test
    @DisplayName("Should get batch statistics successfully")
    void shouldGetBatchStatisticsSuccessfully() {
        // Given
        UUID batchId = UUID.randomUUID();

        when(uploadedFileRepository.countByBatchId(batchId)).thenReturn(20L);
        when(errorLogRepository.countByBatchId(batchId)).thenReturn(5L);

        // When
        Map<String, Object> stats = statisticsService.getBatchStatistics(batchId);

        // Then
        assertNotNull(stats);
        assertEquals(batchId, stats.get("batchId"));
        assertEquals(20L, stats.get("totalFiles"));
        assertEquals(5L, stats.get("totalErrors"));

        verify(uploadedFileRepository).countByBatchId(batchId);
        verify(errorLogRepository).countByBatchId(batchId);
    }

    @Test
    @DisplayName("Should get batch statistics with zero counts")
    void shouldGetBatchStatisticsWithZeroCounts() {
        // Given
        UUID batchId = UUID.randomUUID();

        when(uploadedFileRepository.countByBatchId(batchId)).thenReturn(0L);
        when(errorLogRepository.countByBatchId(batchId)).thenReturn(0L);

        // When
        Map<String, Object> stats = statisticsService.getBatchStatistics(batchId);

        // Then
        assertNotNull(stats);
        assertEquals(0L, stats.get("totalFiles"));
        assertEquals(0L, stats.get("totalErrors"));
    }

    @Test
    @DisplayName("Should handle large numbers in statistics")
    void shouldHandleLargeNumbersInStatistics() {
        // Given
        when(siteRepository.count()).thenReturn(1_000_000L);
        when(batchRepository.count()).thenReturn(10_000_000L);
        when(batchRepository.countActiveBatches()).thenReturn(1_000_000L);
        when(uploadedFileRepository.count()).thenReturn(100_000_000L);
        when(errorLogRepository.count()).thenReturn(5_000_000L);

        // When
        Map<String, Object> stats = statisticsService.getGlobalStatistics();

        // Then
        assertNotNull(stats);
        assertEquals(1_000_000L, stats.get("totalSites"));
        assertEquals(10_000_000L, stats.get("totalBatches"));
        assertEquals(1_000_000L, stats.get("activeBatches"));
        assertEquals(100_000_000L, stats.get("totalFiles"));
        assertEquals(5_000_000L, stats.get("totalErrors"));
    }

    @Test
    @DisplayName("Should get account statistics with multiple sites")
    void shouldGetAccountStatisticsWithMultipleSites() {
        // Given
        UUID accountId = UUID.randomUUID();
        Site site1 = Site.createForTesting(accountId, "site1.example.com", "Site 1");
        Site site2 = Site.createForTesting(accountId, "site2.example.com", "Site 2");
        Site site3 = Site.createForTesting(accountId, "site3.example.com", "Site 3");
        List<Site> allSites = Arrays.asList(site1, site2, site3);
        List<Site> activeSites = Arrays.asList(site1, site3);

        when(siteRepository.countByAccountId(accountId)).thenReturn(3L);
        when(siteRepository.findActiveByAccountId(accountId)).thenReturn(activeSites);
        when(batchRepository.countByAccountId(accountId)).thenReturn(30L);
        when(batchRepository.countActiveBatchesByAccountId(accountId)).thenReturn(5);
        when(uploadedFileRepository.countByAccountId(accountId)).thenReturn(150L);
        when(siteRepository.findByAccountId(accountId)).thenReturn(allSites);
        when(errorLogRepository.countBySiteIds(anyList())).thenReturn(45L);

        // When
        Map<String, Object> stats = statisticsService.getAccountStatistics(accountId);

        // Then
        assertEquals(3L, stats.get("totalSites"));
        assertEquals(2, stats.get("activeSites"));
        assertEquals(30L, stats.get("totalBatches"));
        assertEquals(150L, stats.get("totalFiles"));
        assertEquals(45L, stats.get("totalErrors"));
    }
}
