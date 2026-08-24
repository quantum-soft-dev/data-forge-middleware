package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for PluginAuditService SQL generation logging methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginAuditService")
class PluginAuditServiceTest {

    @Mock
    private PluginAuditLogRepository auditLogRepository;

    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<PluginAuditLog> auditLogCaptor;

    private PluginAuditService auditService;

    private static final String PLUGIN_ID = "bit-bi";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        auditService = new PluginAuditService(auditLogRepository, eventPublisher);
    }

    @Nested
    @DisplayName("SQL Generation Started")
    class SqlGenerationStarted {

        @Test
        @DisplayName("Should log SQL_GENERATION_STARTED with batch and site metadata")
        void shouldLogSqlGenerationStartedWithMetadata() {
            // When
            auditService.logSqlGenerationStarted(PLUGIN_ID, ACCOUNT_ID, BATCH_ID, SITE_ID);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            PluginAuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getPluginId()).isEqualTo(PLUGIN_ID);
            assertThat(savedLog.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(savedLog.getActionType()).isEqualTo(PluginActionType.SQL_GENERATION_STARTED);
            assertThat(savedLog.isSuccess()).isTrue();
            assertThat(savedLog.getMetadata()).isNotNull();
            assertThat(savedLog.getMetadata()).containsEntry("batchId", BATCH_ID.toString());
            assertThat(savedLog.getMetadata()).containsEntry("siteId", SITE_ID.toString());
        }
    }

    @Nested
    @DisplayName("SQL Generation Completed")
    class SqlGenerationCompleted {

        @Test
        @DisplayName("Should log SQL_GENERATION_COMPLETED with stats and s3Key")
        void shouldLogSqlGenerationCompletedWithStats() {
            // Given
            SqlGenerationStats stats = new SqlGenerationStats(10, 5, 2, 3);
            String s3Key = "plugins/bit-bi/account-123/site-name/2025-01-01.sql";
            long durationMs = 1500;

            // When
            auditService.logSqlGenerationCompleted(
                    PLUGIN_ID, ACCOUNT_ID, BATCH_ID, SITE_ID, stats, s3Key, durationMs);

            // Then — deferred to AFTER_COMMIT, so the entry travels on the event, not to the repo
            org.mockito.ArgumentCaptor<com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent> eventCaptor =
                    org.mockito.ArgumentCaptor.forClass(com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            PluginAuditLog savedLog = eventCaptor.getValue().entry();

            assertThat(savedLog.getActionType()).isEqualTo(PluginActionType.SQL_GENERATION_COMPLETED);
            assertThat(savedLog.isSuccess()).isTrue();
            assertThat(savedLog.getDurationMs()).isEqualTo(durationMs);

            Map<String, Object> metadata = savedLog.getMetadata();
            assertThat(metadata).containsEntry("batchId", BATCH_ID.toString());
            assertThat(metadata).containsEntry("siteId", SITE_ID.toString());
            assertThat(metadata).containsEntry("insertCount", 10);
            assertThat(metadata).containsEntry("updateCount", 5);
            assertThat(metadata).containsEntry("deleteCount", 2);
            assertThat(metadata).containsEntry("filesProcessed", 3);
            assertThat(metadata).containsEntry("s3Key", s3Key);
        }
    }

    @Nested
    @DisplayName("SQL Generation Failed")
    class SqlGenerationFailed {

        @Test
        @DisplayName("Should log SQL_GENERATION_FAILED with error message and metadata")
        void shouldLogSqlGenerationFailedWithError() {
            // Given
            String errorMessage = "Table name contains invalid characters: '77test'";
            long durationMs = 500;

            // When
            auditService.logSqlGenerationFailed(
                    PLUGIN_ID, ACCOUNT_ID, BATCH_ID, SITE_ID, errorMessage, durationMs);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            PluginAuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getActionType()).isEqualTo(PluginActionType.SQL_GENERATION_FAILED);
            assertThat(savedLog.isSuccess()).isFalse();
            assertThat(savedLog.getErrorMessage()).isEqualTo(errorMessage);
            assertThat(savedLog.getDurationMs()).isEqualTo(durationMs);

            Map<String, Object> metadata = savedLog.getMetadata();
            assertThat(metadata).containsEntry("batchId", BATCH_ID.toString());
            assertThat(metadata).containsEntry("siteId", SITE_ID.toString());
        }

        @Test
        @DisplayName("Should truncate long error messages")
        void shouldTruncateLongErrorMessages() {
            // Given
            String longErrorMessage = "A".repeat(600);

            // When
            auditService.logSqlGenerationFailed(
                    PLUGIN_ID, ACCOUNT_ID, BATCH_ID, SITE_ID, longErrorMessage, 100);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            PluginAuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getErrorMessage()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("SQL Generation Adopted")
    class SqlGenerationAdopted {

        @Test
        @DisplayName("Should log SQL_GENERATION_ADOPTED naming the adopted generation and the winner's key (#260)")
        void shouldLogSqlGenerationAdoptedWithGenerationAndWinnerKey() {
            UUID generationId = UUID.randomUUID();
            String winnerS3Key = "plugins/bit-bi/account/site/winner.sql";
            long durationMs = 900;

            auditService.logSqlGenerationAdopted(
                    PLUGIN_ID, ACCOUNT_ID, BATCH_ID, SITE_ID, generationId, winnerS3Key, durationMs);

            verify(auditLogRepository).save(auditLogCaptor.capture());
            PluginAuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getPluginId()).isEqualTo(PLUGIN_ID);
            assertThat(savedLog.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(savedLog.getActionType()).isEqualTo(PluginActionType.SQL_GENERATION_ADOPTED);
            assertThat(savedLog.isSuccess()).isTrue();
            assertThat(savedLog.getDurationMs()).isEqualTo(durationMs);

            Map<String, Object> metadata = savedLog.getMetadata();
            assertThat(metadata).containsEntry("batchId", BATCH_ID.toString());
            assertThat(metadata).containsEntry("siteId", SITE_ID.toString());
            assertThat(metadata).containsEntry("generationId", generationId.toString());
            assertThat(metadata).containsEntry("s3Key", winnerS3Key);
        }
    }
}
