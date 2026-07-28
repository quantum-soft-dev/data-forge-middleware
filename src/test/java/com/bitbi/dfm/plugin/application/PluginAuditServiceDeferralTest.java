package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Which audit entries wait for the caller's transaction, and which must not.
 * <p>
 * Every audit write opens its own transaction. Written inline from a caller that is still inside
 * a transaction, the row survives a rollback — so an audit describing a <em>state change</em> can
 * end up asserting something the database undid.
 * </p>
 * <p>
 * The opposite mistake is just as real, which is why this is a per-method decision rather than a
 * blanket one. {@code logReinitFailed} is called inside a transaction and followed immediately by
 * a throw; deferring it to AFTER_COMMIT would drop the record of every failure it exists to
 * capture. Failures and observations must be written regardless of the caller's outcome.
 * </p>
 * <p>
 * A third case sits between the two: clear, reinit and delete-generation destroy S3 objects
 * before their transaction commits, and no rollback brings those back. Waiting for the commit is
 * still right — but a rollback there is not a non-event, so those entries also carry a failure
 * entry recording that the database was restored and the files were not.
 * </p>
 */
@DisplayName("PluginAuditService — transaction-aware audit writes")
class PluginAuditServiceDeferralTest {

    private static final String PLUGIN_ID = "bit-bi";

    private PluginAuditLogRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private PluginAuditService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        repository = mock(PluginAuditLogRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new PluginAuditService(repository, eventPublisher);
        accountId = UUID.randomUUID();
    }

    private PluginAuditEntryReadyEvent deferredEvent() {
        ArgumentCaptor<PluginAuditEntryReadyEvent> captor =
                ArgumentCaptor.forClass(PluginAuditEntryReadyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        verify(repository, never()).save(any());
        return captor.getValue();
    }

    private PluginAuditLog deferredEntry() {
        return deferredEvent().entry();
    }

    @Nested
    @DisplayName("state changes wait for the commit")
    class Deferred {

        @Test
        @DisplayName("history cleared")
        void shouldDeferHistoryCleared() {
            service.logHistoryCleared(PLUGIN_ID, accountId, 3L, 2L, 1024L);

            assertThat(deferredEntry().getActionType())
                    .isEqualTo(PluginActionType.PLUGIN_HISTORY_CLEARED);
        }

        @Test
        @DisplayName("reinit")
        void shouldDeferReinit() {
            service.logReinit(PLUGIN_ID, accountId, 3L, 2L, true, UUID.randomUUID());

            assertThat(deferredEntry().getActionType()).isEqualTo(PluginActionType.REINIT);
        }

        @Test
        @DisplayName("generation deleted")
        void shouldDeferGenerationDeleted() {
            service.logGenerationDeleted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(), 10L);

            assertThat(deferredEntry().getActionType())
                    .isEqualTo(PluginActionType.SQL_GENERATION_DELETED);
        }

        @Test
        @DisplayName("one-time download link consumed")
        void shouldDeferLinkConsumed() {
            service.logLinkConsumed("parquet-export", accountId, "part-0.parquet", "s3/key");

            assertThat(deferredEntry().getActionType()).isEqualTo(PluginActionType.LINK_CONSUMED);
        }

        @Test
        @DisplayName("SQL generation completed — DeltaSqlQueueService.processNextPending supplies the transaction")
        void shouldDeferSqlGenerationCompleted() {
            service.logSqlGenerationCompleted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(),
                    new com.bitbi.dfm.plugin.domain.SqlGenerationStats(1, 0, 0, 1), "s3/key", 5L);

            assertThat(deferredEntry().getActionType())
                    .isEqualTo(PluginActionType.SQL_GENERATION_COMPLETED);
        }

        @Test
        @DisplayName("SQL generation completed with no changes")
        void shouldDeferSqlGenerationCompletedNoChanges() {
            service.logSqlGenerationCompletedNoChanges(
                    PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(), 5L);

            assertThat(deferredEntry().getActionType())
                    .isEqualTo(PluginActionType.SQL_GENERATION_COMPLETED);
        }

        @Test
        @DisplayName("SQL regeneration completed — PluginHistoryService.regenerateSql supplies the transaction")
        void shouldDeferSqlRegenerationCompleted() {
            service.logSqlRegenerationCompleted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), new com.bitbi.dfm.plugin.domain.SqlGenerationStats(1, 0, 0, 1), 5L);

            assertThat(deferredEntry().getActionType())
                    .isEqualTo(PluginActionType.SQL_REGENERATION_COMPLETED);
        }

        @Test
        @DisplayName("credential rotated")
        void shouldDeferCredentialRotated() {
            service.logCredentialRotated(PLUGIN_ID, accountId, PluginActionType.API_KEY_ROTATED);

            assertThat(deferredEntry().getActionType()).isEqualTo(PluginActionType.API_KEY_ROTATED);
        }
    }

    @Nested
    @DisplayName("state changes a rollback cannot fully undo also carry a rollback entry")
    class IrreversibleOnRollback {

        @Test
        @DisplayName("history cleared — the S3 files stay deleted even when the rows come back")
        void shouldCarryRollbackEntryForHistoryCleared() {
            service.logHistoryCleared(PLUGIN_ID, accountId, 3L, 2L, 1024L);

            PluginAuditLog rollbackEntry = deferredEvent().rollbackEntry();

            assertThat(rollbackEntry).isNotNull();
            assertThat(rollbackEntry.getActionType())
                    .isEqualTo(PluginActionType.PLUGIN_HISTORY_CLEARED);
            assertThat(rollbackEntry.isSuccess()).isFalse();
            assertThat(rollbackEntry.getErrorMessage()).contains("S3");
            assertThat(rollbackEntry.getMetadata()).containsEntry("deletedFilesCount", 2L);
        }

        @Test
        @DisplayName("reinit")
        void shouldCarryRollbackEntryForReinit() {
            service.logReinit(PLUGIN_ID, accountId, 3L, 2L, true, UUID.randomUUID());

            PluginAuditLog rollbackEntry = deferredEvent().rollbackEntry();

            assertThat(rollbackEntry).isNotNull();
            assertThat(rollbackEntry.getActionType()).isEqualTo(PluginActionType.REINIT);
            assertThat(rollbackEntry.isSuccess()).isFalse();
            assertThat(rollbackEntry.getMetadata())
                    .as("the metadata flag must not keep claiming success")
                    .containsEntry("success", false);
        }

        @Test
        @DisplayName("generation deleted")
        void shouldCarryRollbackEntryForGenerationDeleted() {
            service.logGenerationDeleted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(), 10L);

            PluginAuditLog rollbackEntry = deferredEvent().rollbackEntry();

            assertThat(rollbackEntry).isNotNull();
            assertThat(rollbackEntry.getActionType())
                    .isEqualTo(PluginActionType.SQL_GENERATION_DELETED);
            assertThat(rollbackEntry.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("a wholly transactional change carries nothing — the rollback undid all of it")
        void shouldCarryNoRollbackEntryForCredentialRotation() {
            service.logCredentialRotated(PLUGIN_ID, accountId, PluginActionType.API_KEY_ROTATED);

            assertThat(deferredEvent().rollbackEntry()).isNull();
        }

        @Test
        @DisplayName("SQL generation completed carries nothing either")
        void shouldCarryNoRollbackEntryForSqlGenerationCompleted() {
            service.logSqlGenerationCompleted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(),
                    new com.bitbi.dfm.plugin.domain.SqlGenerationStats(1, 0, 0, 1), "s3/key", 5L);

            assertThat(deferredEvent().rollbackEntry()).isNull();
        }
    }

    @Nested
    @DisplayName("failures are written regardless of the caller's outcome")
    class NotDeferred {

        @Test
        @DisplayName("reinit failed — the caller throws right after, so deferring would lose it")
        void shouldWriteReinitFailureImmediately() {
            service.logReinitFailed(PLUGIN_ID, accountId, "plugin is not active");

            verify(repository).save(any(PluginAuditLog.class));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("SQL generation started — an attempt that later rolls back still happened")
        void shouldWriteSqlGenerationStartedImmediately() {
            service.logSqlGenerationStarted(PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID());

            verify(repository).save(any(PluginAuditLog.class));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("SQL generation failed")
        void shouldWriteSqlGenerationFailureImmediately() {
            service.logSqlGenerationFailed(
                    PLUGIN_ID, accountId, UUID.randomUUID(), UUID.randomUUID(), "boom", 5L);

            verify(repository).save(any(PluginAuditLog.class));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("download link rejected — nothing changed, so there is nothing to wait for")
        void shouldWriteLinkRejectionImmediately() {
            service.logLinkRejected("parquet-export", accountId, "expired");

            verify(repository).save(any(PluginAuditLog.class));
            verifyNoInteractions(eventPublisher);
        }
    }
}
