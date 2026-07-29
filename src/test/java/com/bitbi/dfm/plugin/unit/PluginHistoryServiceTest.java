package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.plugin.presentation.dto.*;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PluginHistoryService.
 *
 * <p>Feature 014: Plugin History Management</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginHistoryService Unit Tests")
class PluginHistoryServiceTest {

    @Mock
    private PluginSqlGenerationRepository sqlGenerationRepository;

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private S3SqlFileStorageService s3StorageService;

    @Mock
    private PluginAuditService auditService;

    @Mock
    private SqlGenerationService sqlGenerationService;

    @Mock
    private com.bitbi.dfm.plugin.application.PluginDeltaBaselineService pluginDeltaBaselineService;

    @InjectMocks
    private PluginHistoryService pluginHistoryService;

    private static final String PLUGIN_ID = "bit-bi";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID GENERATION_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final Long ACCOUNT_PLUGIN_ID = 123L;

    private AccountPlugin mockAccountPlugin;
    private PluginSqlGeneration mockGeneration;
    private Site mockSite;

    @BeforeEach
    void setUp() {
        mockAccountPlugin = mock(AccountPlugin.class);
        when(mockAccountPlugin.getId()).thenReturn(ACCOUNT_PLUGIN_ID);

        mockGeneration = mock(PluginSqlGeneration.class);
        when(mockGeneration.getId()).thenReturn(GENERATION_ID);
        when(mockGeneration.getAccountPluginId()).thenReturn(ACCOUNT_PLUGIN_ID);
        when(mockGeneration.getSiteId()).thenReturn(SITE_ID);
        when(mockGeneration.getSourceBatchId()).thenReturn(BATCH_ID);
        when(mockGeneration.getS3Key()).thenReturn("plugins/bit-bi/test/file.sql");

        mockSite = mock(Site.class);
        when(mockSite.getDomain()).thenReturn("test-site.example.com");
    }

    // ==================== User Story 1: View History ====================

    @Nested
    @DisplayName("listGenerations")
    class ListGenerations {

        @Test
        @DisplayName("T014: Should return paginated list of generations")
        void shouldReturnPaginatedListOfGenerations() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Page<PluginSqlGeneration> generationPage = new PageImpl<>(List.of(mockGeneration));
            when(sqlGenerationRepository.findByAccountPluginId(eq(ACCOUNT_PLUGIN_ID), eq(false), any(Pageable.class)))
                    .thenReturn(generationPage);

            // Mock batch site lookup (uses findAllById for N+1 prevention)
            when(mockSite.getId()).thenReturn(SITE_ID);
            when(siteRepository.findAllById(any())).thenReturn(List.of(mockSite));

            Pageable pageable = PageRequest.of(0, 20);

            // When
            Page<SqlGenerationSummaryDto> result = pluginHistoryService.listGenerations(
                    PLUGIN_ID, ACCOUNT_ID, false, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).siteDomain()).isEqualTo("test-site.example.com");

            verify(accountPluginRepository).findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID);
            verify(sqlGenerationRepository).findByAccountPluginId(ACCOUNT_PLUGIN_ID, false, pageable);
        }

        @Test
        @DisplayName("Should throw when account plugin not found")
        void shouldThrowWhenAccountPluginNotFound() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.empty());

            Pageable pageable = PageRequest.of(0, 20);

            // When / Then
            assertThatThrownBy(() -> pluginHistoryService.listGenerations(PLUGIN_ID, ACCOUNT_ID, false, pageable))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No plugin activation found");
        }
    }

    @Nested
    @DisplayName("getSqlContent")
    class GetSqlContent {

        @Test
        @DisplayName("T015: Should return paginated SQL statements")
        void shouldReturnPaginatedSqlStatements() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlGenerationRepository.findById(GENERATION_ID))
                    .thenReturn(Optional.of(mockGeneration));

            String sqlContent = """
                    INSERT INTO customers (id, name) VALUES ('1', 'Alice');
                    --- END OF COMMAND ---
                    UPDATE users SET status = 'active' WHERE id = '2';
                    --- END OF COMMAND ---
                    DELETE FROM temp_data WHERE created_at < '2024-01-01';
                    --- END OF COMMAND ---
                    """;
            when(s3StorageService.getSqlFileContent(anyString())).thenReturn(sqlContent);

            // When
            SqlContentPageDto result = pluginHistoryService.getSqlContent(
                    PLUGIN_ID, ACCOUNT_ID, GENERATION_ID, 0, 2);

            // Then
            assertThat(result.generationId()).isEqualTo(GENERATION_ID);
            assertThat(result.statements()).hasSize(2);
            assertThat(result.totalStatements()).isEqualTo(3);
            assertThat(result.hasNext()).isTrue();
            assertThat(result.hasPrevious()).isFalse();
        }

        @Test
        @DisplayName("Should return empty statements for empty SQL")
        void shouldReturnEmptyStatementsForEmptySql() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlGenerationRepository.findById(GENERATION_ID))
                    .thenReturn(Optional.of(mockGeneration));
            when(s3StorageService.getSqlFileContent(anyString())).thenReturn("");

            // When
            SqlContentPageDto result = pluginHistoryService.getSqlContent(
                    PLUGIN_ID, ACCOUNT_ID, GENERATION_ID, 0, 100);

            // Then
            assertThat(result.statements()).isEmpty();
            assertThat(result.totalStatements()).isZero();
        }

        @Test
        @DisplayName("Should throw when generation not found")
        void shouldThrowWhenGenerationNotFound() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlGenerationRepository.findById(GENERATION_ID))
                    .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> pluginHistoryService.getSqlContent(
                    PLUGIN_ID, ACCOUNT_ID, GENERATION_ID, 0, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Generation not found");
        }

        @Test
        @DisplayName("Should throw when generation belongs to different account-plugin")
        void shouldThrowWhenGenerationBelongsToDifferentAccountPlugin() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            PluginSqlGeneration differentGeneration = mock(PluginSqlGeneration.class);
            when(differentGeneration.getAccountPluginId()).thenReturn(999L);
            when(sqlGenerationRepository.findById(GENERATION_ID))
                    .thenReturn(Optional.of(differentGeneration));

            // When / Then
            assertThatThrownBy(() -> pluginHistoryService.getSqlContent(
                    PLUGIN_ID, ACCOUNT_ID, GENERATION_ID, 0, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to account-plugin");
        }
    }

    // ==================== User Story 2: Clear History ====================

    @Nested
    @DisplayName("getHistorySummary")
    class GetHistorySummary {

        @Test
        @DisplayName("T032: Should return history summary with counts")
        void shouldReturnHistorySummaryWithCounts() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Object[] countAndSum = new Object[]{42L, 1234567L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            // Mock no active batches (empty site list)
            when(siteRepository.findSiteIdsByAccountId(ACCOUNT_ID)).thenReturn(List.of());

            // When
            HistoryClearSummaryDto result = pluginHistoryService.getHistorySummary(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.pluginId()).isEqualTo(PLUGIN_ID);
            assertThat(result.generationCount()).isEqualTo(42L);
            assertThat(result.totalFileSizeBytes()).isEqualTo(1234567L);
            assertThat(result.pluginWillBeDeactivated()).isTrue();
            assertThat(result.hasActiveBatches()).isFalse();
        }

        @Test
        @DisplayName("Should detect active batches when present")
        void shouldDetectActiveBatchesWhenPresent() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Object[] countAndSum = new Object[]{5L, 50000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            // Mock active batch exists
            when(siteRepository.findSiteIdsByAccountId(ACCOUNT_ID)).thenReturn(List.of(SITE_ID));
            when(batchRepository.findActiveBySiteId(SITE_ID)).thenReturn(Optional.of(mock(com.bitbi.dfm.batch.domain.Batch.class)));

            // When
            HistoryClearSummaryDto result = pluginHistoryService.getHistorySummary(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.hasActiveBatches()).isTrue();
        }
    }

    @Nested
    @DisplayName("clearHistory")
    class ClearHistory {

        @Test
        @DisplayName("T033: Should delete S3 files and database records")
        void shouldDeleteS3FilesAndDatabaseRecords() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Object[] countAndSum = new Object[]{42L, 1234567L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            List<String> s3Keys = List.of("key1.sql", "key2.sql");
            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(s3Keys);

            // Mock batch deletion - returns empty list (all successful)
            when(s3StorageService.deleteFiles(s3Keys)).thenReturn(List.of());

            // When
            HistoryClearResultDto result = pluginHistoryService.clearHistory(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.deletedGenerations()).isEqualTo(42L);
            assertThat(result.deletedFilesCount()).isEqualTo(2L);
            assertThat(result.failedS3Keys()).isEmpty();
            assertThat(result.pluginDeactivated()).isTrue();

            verify(s3StorageService).deleteFiles(s3Keys);
            verify(sqlGenerationRepository).deleteByAccountPluginId(ACCOUNT_PLUGIN_ID);
            verify(mockAccountPlugin).deactivate();
            verify(accountPluginRepository).save(mockAccountPlugin);
            verify(auditService).logHistoryCleared(eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(42L), eq(2L), eq(1234567L));
        }

        @Test
        @DisplayName("Should collect failed S3 deletions")
        void shouldCollectFailedS3Deletions() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Object[] countAndSum = new Object[]{2L, 1000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            List<String> s3Keys = List.of("key1.sql", "key2.sql");
            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(s3Keys);

            // Mock batch deletion - returns list of failed keys
            when(s3StorageService.deleteFiles(s3Keys)).thenReturn(List.of("key2.sql"));

            // When
            HistoryClearResultDto result = pluginHistoryService.clearHistory(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.deletedFilesCount()).isEqualTo(1L); // Only 1 succeeded
            assertThat(result.failedS3Keys()).containsExactly("key2.sql");
            // The audit decides from this count whether a rollback still destroyed something,
            // so it must be the number that actually left the bucket — not the attempt count.
            verify(auditService).logHistoryCleared(eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(2L), eq(1L), eq(1000L));
        }

        @Test
        @DisplayName("Should report zero deleted files to the audit when every S3 delete failed")
        void shouldReportZeroDeletedFilesWhenAllS3DeletionsFailed() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            Object[] countAndSum = new Object[]{2L, 1000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            List<String> s3Keys = List.of("key1.sql", "key2.sql");
            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(s3Keys);
            when(s3StorageService.deleteFiles(s3Keys)).thenReturn(s3Keys);

            // When
            pluginHistoryService.clearHistory(PLUGIN_ID, ACCOUNT_ID);

            // Then — nothing left the bucket, so a rollback would undo the whole operation
            verify(auditService).logHistoryCleared(eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(2L), eq(0L), eq(1000L));
        }
    }

    // ==================== User Story 4: Reinit (Feature 015) ====================

    @Nested
    @DisplayName("reinit - Feature 015")
    class Reinit {

        @Test
        @DisplayName("T017: Should reinit successfully - delete history and trigger async SQL generation")
        void shouldReinitSuccessfully() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(mockAccountPlugin.isActive()).thenReturn(true);

            Object[] countAndSum = new Object[]{10L, 50000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            List<String> s3Keys = List.of("key1.sql", "key2.sql", "key3.sql");
            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(s3Keys);

            // Mock batch deletion - returns empty list (all successful)
            when(s3StorageService.deleteFiles(s3Keys)).thenReturn(List.of());

            com.bitbi.dfm.batch.domain.Batch mockBatch = mock(com.bitbi.dfm.batch.domain.Batch.class);
            when(mockBatch.getId()).thenReturn(BATCH_ID);
            when(batchRepository.findLatestCompletedByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(mockBatch));

            // When
            ReinitResultDto result = pluginHistoryService.reinit(PLUGIN_ID, ACCOUNT_ID);

            // Then - reinit now sets baseline batch instead of triggering SQL generation
            assertThat(result.success()).isTrue();
            assertThat(result.deletedGenerations()).isEqualTo(10L);
            assertThat(result.deletedS3Files()).isEqualTo(3L);
            assertThat(result.totalBytesFreed()).isEqualTo(50000L);
            assertThat(result.sqlGenerationTriggered()).isFalse(); // No longer triggers SQL generation
            assertThat(result.batchId()).isEqualTo(BATCH_ID);
            assertThat(result.message()).contains("baseline batch");

            // Verify S3 files deleted via batch method
            verify(s3StorageService).deleteFiles(s3Keys);

            // Verify DB records deleted
            verify(sqlGenerationRepository).deleteByAccountPluginId(ACCOUNT_PLUGIN_ID);

            // Verify baseline batch is set (SQL generation no longer triggered)
            verify(mockAccountPlugin).setBaselineBatchId(BATCH_ID);
            verify(accountPluginRepository).save(mockAccountPlugin);

            // Verify audit logged (sqlGenerationTriggered = false now)
            verify(auditService).logReinit(eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(10L), eq(3L), eq(false), eq(BATCH_ID));

            // Verify plugin NOT deactivated (key difference from clearHistory)
            verify(mockAccountPlugin, never()).deactivate();

            // 026: per-table delta baselines recaptured on reinit
            verify(pluginDeltaBaselineService).recaptureForReinit(mockAccountPlugin);
        }

        @Test
        @DisplayName("T018: Should reject reinit for inactive plugin (FR-009)")
        void shouldRejectReinitForInactivePlugin() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(mockAccountPlugin.isActive()).thenReturn(false);

            // When / Then
            assertThatThrownBy(() -> pluginHistoryService.reinit(PLUGIN_ID, ACCOUNT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not active");

            // Verify failure was audited
            verify(auditService).logReinitFailed(eq(PLUGIN_ID), eq(ACCOUNT_ID), contains("is not active"));

            // Verify no deletions occurred
            verifyNoInteractions(s3StorageService);
            verify(sqlGenerationRepository, never()).deleteByAccountPluginId(any());
        }

        @Test
        @DisplayName("T019: Should preserve API key on reinit (not regenerate)")
        void shouldPreserveApiKeyOnReinit() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(mockAccountPlugin.isActive()).thenReturn(true);

            Object[] countAndSum = new Object[]{5L, 25000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(List.of());

            when(batchRepository.findLatestCompletedByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // When
            ReinitResultDto result = pluginHistoryService.reinit(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.success()).isTrue();
            assertThat(result.sqlGenerationTriggered()).isFalse();
            assertThat(result.message()).contains("No completed batches");

            // Key assertion: plugin should NOT be deactivated (preserves API key)
            verify(mockAccountPlugin, never()).deactivate();
            // Note: save() IS called to clear baselineBatchId when no batches exist
            verify(mockAccountPlugin).setBaselineBatchId(null);
            verify(accountPluginRepository).save(mockAccountPlugin);
        }

        @Test
        @DisplayName("Reinit sets baseline batch when completed batch exists")
        void reinitSetsBaselineBatchWhenCompletedBatchExists() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(mockAccountPlugin.isActive()).thenReturn(true);

            Object[] countAndSum = new Object[]{5L, 25000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(List.of());

            com.bitbi.dfm.batch.domain.Batch mockBatch = mock(com.bitbi.dfm.batch.domain.Batch.class);
            when(mockBatch.getId()).thenReturn(BATCH_ID);
            when(batchRepository.findLatestCompletedByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(mockBatch));

            // When
            ReinitResultDto result = pluginHistoryService.reinit(PLUGIN_ID, ACCOUNT_ID);

            // Then - reinit sets baseline batch instead of triggering SQL generation
            assertThat(result.success()).isTrue();
            assertThat(result.sqlGenerationTriggered()).isFalse(); // No longer triggers SQL
            assertThat(result.deletedGenerations()).isEqualTo(5L);
            assertThat(result.batchId()).isEqualTo(BATCH_ID);
            assertThat(result.message()).contains("baseline batch");

            // Verify deletion still happened
            verify(sqlGenerationRepository).deleteByAccountPluginId(ACCOUNT_PLUGIN_ID);

            // Verify baseline batch is set
            verify(mockAccountPlugin).setBaselineBatchId(BATCH_ID);
            verify(accountPluginRepository).save(mockAccountPlugin);

            // SQL generation is no longer triggered on reinit
            verifyNoInteractions(sqlGenerationService);
        }

        @Test
        @DisplayName("Should handle S3 deletion failures gracefully")
        void shouldHandleS3DeletionFailuresGracefully() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(mockAccountPlugin.isActive()).thenReturn(true);

            Object[] countAndSum = new Object[]{3L, 15000L};
            when(sqlGenerationRepository.countAndSumByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(countAndSum);

            List<String> s3Keys = List.of("key1.sql", "key2.sql", "key3.sql");
            when(sqlGenerationRepository.findS3KeysByAccountPluginId(ACCOUNT_PLUGIN_ID))
                    .thenReturn(s3Keys);

            // Mock batch deletion - returns list of failed keys
            when(s3StorageService.deleteFiles(s3Keys)).thenReturn(List.of("key2.sql"));

            when(batchRepository.findLatestCompletedByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // When
            ReinitResultDto result = pluginHistoryService.reinit(PLUGIN_ID, ACCOUNT_ID);

            // Then
            assertThat(result.success()).isTrue();
            assertThat(result.deletedS3Files()).isEqualTo(2L); // Only 2 succeeded
            assertThat(result.s3DeleteWarnings()).containsExactly("key2.sql");
        }
    }

    // ==================== User Story 3: Regenerate ====================

    @Nested
    @DisplayName("regenerateSql")
    class RegenerateSql {

        @Test
        @DisplayName("T045: Should throw when generation already superseded")
        void shouldThrowWhenGenerationAlreadySuperseded() {
            // Given
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));

            PluginSqlGeneration supersededGeneration = mock(PluginSqlGeneration.class);
            when(supersededGeneration.getAccountPluginId()).thenReturn(ACCOUNT_PLUGIN_ID);
            when(supersededGeneration.isSuperseded()).thenReturn(true);
            when(sqlGenerationRepository.findById(GENERATION_ID))
                    .thenReturn(Optional.of(supersededGeneration));

            // When / Then
            assertThatThrownBy(() -> pluginHistoryService.regenerateSql(PLUGIN_ID, ACCOUNT_ID, GENERATION_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already superseded");
        }

        @Test
        @DisplayName("T046: Should successfully regenerate SQL for active generation")
        @Disabled("Requires full SqlGenerationService mock setup - tested via integration tests")
        void shouldSuccessfullyRegenerateSql() {
            // This test requires proper SqlGenerationService mock setup which would
            // need a @Mock for SqlGenerationService and proper wiring.
            // The regeneration flow is better tested via integration tests.
        }
    }

    // ==================== User Story: Delete Single Generation ====================

    @Nested
    @DisplayName("deleteGeneration")
    class DeleteGeneration {

        @BeforeEach
        void stubLookups() {
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, PLUGIN_ID))
                    .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlGenerationRepository.findById(GENERATION_ID)).thenReturn(Optional.of(mockGeneration));
            when(mockGeneration.getFileSizeBytes()).thenReturn(4096L);
        }

        @Test
        @DisplayName("Should tell the audit the S3 file is gone when the delete succeeded")
        void shouldReportTheFileAsDeleted() {
            when(s3StorageService.deleteFiles(List.of("plugins/bit-bi/test/file.sql")))
                    .thenReturn(List.of());

            pluginHistoryService.deleteGeneration(PLUGIN_ID, ACCOUNT_ID, GENERATION_ID);

            verify(auditService).logGenerationDeleted(
                    eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(GENERATION_ID), eq(BATCH_ID), eq(4096L), eq(true));
        }

        @Test
        @DisplayName("Should tell the audit nothing was destroyed when the S3 delete failed")
        void shouldReportTheFileAsSurvivingWhenS3DeleteFails() {
            when(s3StorageService.deleteFiles(List.of("plugins/bit-bi/test/file.sql")))
                    .thenReturn(List.of("plugins/bit-bi/test/file.sql"));

            pluginHistoryService.deleteGeneration(PLUGIN_ID, ACCOUNT_ID, GENERATION_ID);

            verify(auditService).logGenerationDeleted(
                    eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(GENERATION_ID), eq(BATCH_ID), eq(4096L), eq(false));
        }

        @Test
        @DisplayName("Should tell the audit nothing was destroyed when there was no S3 key")
        void shouldReportTheFileAsSurvivingWhenThereIsNoS3Key() {
            when(mockGeneration.getS3Key()).thenReturn("  ");

            pluginHistoryService.deleteGeneration(PLUGIN_ID, ACCOUNT_ID, GENERATION_ID);

            verify(s3StorageService, never()).deleteFiles(any());
            verify(auditService).logGenerationDeleted(
                    eq(PLUGIN_ID), eq(ACCOUNT_ID), eq(GENERATION_ID), eq(BATCH_ID), eq(4096L), eq(false));
        }
    }
}
