package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.account.application.AccountProperties;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.exception.HeartbeatRequiredException;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BatchLifecycleService.
 * Covers the new SiteInactiveException and SchemaRequiredException paths (feature/019).
 */
@DisplayName("BatchLifecycleService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchLifecycleServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteSchemaService siteSchemaService;

    private AccountProperties accountProperties;
    private BatchLifecycleService service;

    private UUID accountId;
    private UUID siteId;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        accountProperties = new AccountProperties();
        accountProperties.setMaxConcurrentBatches(5);
        service = new BatchLifecycleService(batchRepository, eventPublisher, accountProperties,
                siteRepository, siteSchemaService, 5, "2026-12-31");
        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        batchId = UUID.randomUUID();
    }

    // --- helpers ---

    private Site mockActiveSite(SiteType siteType) {
        Site site = mock(Site.class);
        when(site.getIsActive()).thenReturn(true);
        when(site.getSiteType()).thenReturn(siteType);
        when(site.getLastHeartbeatAt()).thenReturn(LocalDateTime.now()); // recent heartbeat
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        return site;
    }

    private void setupNoActiveBatchForSite() {
        when(batchRepository.findActiveBySiteId(siteId)).thenReturn(Optional.empty());
    }

    private void setupConcurrentBatchCount(int count) {
        when(batchRepository.countActiveBatchesByAccountIdWithLock(accountId)).thenReturn(count);
    }

    private Batch mockSavedBatch() {
        Batch batch = Batch.start(accountId, siteId);
        when(batchRepository.save(any(Batch.class))).thenReturn(batch);
        return batch;
    }

    @Nested
    @DisplayName("startBatch() — site validation")
    class StartBatchSiteValidation {

        @Test
        @DisplayName("should throw SiteInactiveException when site is deactivated")
        void shouldThrowWhenSiteInactive() {
            Site site = mock(Site.class);
            when(site.getIsActive()).thenReturn(false);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(BatchLifecycleService.SiteInactiveException.class)
                    .hasMessageContaining("Cannot start batch for inactive site");
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when site not found")
        void shouldThrowWhenSiteNotFound() {
            when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Site not found");
        }
    }

    @Nested
    @DisplayName("startBatch() — heartbeat validation")
    class StartBatchHeartbeatValidation {

        @Test
        @DisplayName("should throw HeartbeatRequiredException when no heartbeat recorded")
        void shouldThrowWhenNoHeartbeat() {
            Site site = mock(Site.class);
            when(site.getIsActive()).thenReturn(true);
            when(site.getId()).thenReturn(siteId);
            when(site.getLastHeartbeatAt()).thenReturn(null);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(HeartbeatRequiredException.class)
                    .hasMessageContaining("Heartbeat required");
        }

        @Test
        @DisplayName("should throw HeartbeatRequiredException when heartbeat is expired")
        void shouldThrowWhenHeartbeatExpired() {
            Site site = mock(Site.class);
            when(site.getIsActive()).thenReturn(true);
            when(site.getId()).thenReturn(siteId);
            when(site.getLastHeartbeatAt()).thenReturn(LocalDateTime.now().minusMinutes(10)); // older than 5min interval
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(HeartbeatRequiredException.class);
        }

        @Test
        @DisplayName("should pass heartbeat check when heartbeat is recent")
        void shouldPassWhenHeartbeatRecent() {
            mockActiveSite(SiteType.DBF); // sets recent heartbeat
            setupNoActiveBatchForSite();
            setupConcurrentBatchCount(0);
            mockSavedBatch();

            Batch result = service.startBatch(accountId, siteId);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("startBatch() — POSTGRES_CDC schema requirement")
    class StartBatchCdcSchemaRequirement {

        @Test
        @DisplayName("should throw SchemaRequiredException for CDC site without schema")
        void shouldThrowForCdcSiteWithoutSchema() {
            mockActiveSite(SiteType.POSTGRES_CDC);
            when(siteSchemaService.hasSchema(siteId)).thenReturn(false);

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(BatchLifecycleService.SchemaRequiredException.class)
                    .hasMessageContaining("Schema required for POSTGRES_CDC sites");
        }

        @Test
        @DisplayName("should allow CDC site with schema to start batch")
        void shouldAllowCdcSiteWithSchema() {
            mockActiveSite(SiteType.POSTGRES_CDC);
            when(siteSchemaService.hasSchema(siteId)).thenReturn(true);
            setupNoActiveBatchForSite();
            setupConcurrentBatchCount(0);
            mockSavedBatch();

            Batch result = service.startBatch(accountId, siteId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should allow DBF site without schema during grace period (warning only)")
        void shouldAllowDbfSiteWithoutSchemaDuringGracePeriod() {
            mockActiveSite(SiteType.DBF);
            when(siteSchemaService.hasSchema(siteId)).thenReturn(false);
            setupNoActiveBatchForSite();
            setupConcurrentBatchCount(0);
            mockSavedBatch();

            Batch result = service.startBatch(accountId, siteId);

            // DBF sites without schema are allowed during grace period (warning logged, no exception)
            assertThat(result).isNotNull();
            verify(siteSchemaService).hasSchema(siteId);
        }
    }

    @Nested
    @DisplayName("startBatch() — batch limit validation")
    class StartBatchLimitValidation {

        @Test
        @DisplayName("should throw ActiveBatchExistsException when site already has active batch")
        void shouldThrowWhenActiveHatchExists() {
            mockActiveSite(SiteType.DBF);
            Batch existingBatch = mock(Batch.class);
            when(batchRepository.findActiveBySiteId(siteId)).thenReturn(Optional.of(existingBatch));

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(BatchLifecycleService.ActiveBatchExistsException.class)
                    .hasMessageContaining("already has an active batch");
        }

        @Test
        @DisplayName("should throw ConcurrentBatchLimitException when account at limit")
        void shouldThrowWhenConcurrentLimitReached() {
            mockActiveSite(SiteType.DBF);
            setupNoActiveBatchForSite();
            when(batchRepository.countActiveBatchesByAccountIdWithLock(accountId)).thenReturn(5); // at limit

            assertThatThrownBy(() -> service.startBatch(accountId, siteId))
                    .isInstanceOf(BatchLifecycleService.ConcurrentBatchLimitException.class)
                    .hasMessageContaining("concurrent batch limit");
        }
    }

    @Nested
    @DisplayName("startBatch() — successful start")
    class StartBatchSuccess {

        @Test
        @DisplayName("should return saved batch and publish event")
        void shouldReturnBatchAndPublishEvent() {
            mockActiveSite(SiteType.DBF);
            setupNoActiveBatchForSite();
            setupConcurrentBatchCount(2);
            Batch savedBatch = mockSavedBatch();

            Batch result = service.startBatch(accountId, siteId);

            assertThat(result).isNotNull();
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("completeBatch()")
    class CompleteBatch {

        @Test
        @DisplayName("should throw BatchNotFoundException when batch not found")
        void shouldThrowWhenBatchNotFound() {
            when(batchRepository.findById(batchId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeBatch(batchId))
                    .isInstanceOf(BatchLifecycleService.BatchNotFoundException.class);
        }

        @Test
        @DisplayName("should throw InvalidBatchStatusException when batch is not IN_PROGRESS")
        void shouldThrowWhenNotInProgress() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(batch.getId()).thenReturn(batchId);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> service.completeBatch(batchId))
                    .isInstanceOf(BatchLifecycleService.InvalidBatchStatusException.class);
        }

        @Test
        @DisplayName("should complete batch and publish event")
        void shouldCompleteBatchAndPublishEvent() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getAccountId()).thenReturn(accountId);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.save(batch)).thenReturn(batch);

            service.completeBatch(batchId);

            verify(batch).complete();
            verify(batchRepository).save(batch);
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("failBatch()")
    class FailBatch {

        @Test
        @DisplayName("should fail batch that is IN_PROGRESS")
        void shouldFailInProgressBatch() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.save(batch)).thenReturn(batch);

            service.failBatch(batchId);

            verify(batch).fail();
        }

        @Test
        @DisplayName("should throw InvalidBatchStatusException when batch is CANCELLED")
        void shouldThrowWhenBatchCancelled() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.CANCELLED);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> service.failBatch(batchId))
                    .isInstanceOf(BatchLifecycleService.InvalidBatchStatusException.class);
        }
    }

    @Nested
    @DisplayName("cancelBatch()")
    class CancelBatch {

        @Test
        @DisplayName("should cancel batch that is IN_PROGRESS")
        void shouldCancelInProgressBatch() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.save(batch)).thenReturn(batch);

            service.cancelBatch(batchId);

            verify(batch).cancel();
        }
    }

    @Nested
    @DisplayName("markBatchNotCompleted()")
    class MarkBatchNotCompleted {

        @Test
        @DisplayName("should mark IN_PROGRESS batch as NOT_COMPLETED and publish event")
        void shouldMarkAndPublishEvent() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getAccountId()).thenReturn(accountId);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.save(batch)).thenReturn(batch);

            service.markBatchNotCompleted(batchId);

            verify(batch).markAsNotCompleted();
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("should return batch unchanged when already COMPLETED (not IN_PROGRESS)")
        void shouldReturnUnchangedWhenNotInProgress() {
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

            Batch result = service.markBatchNotCompleted(batchId);

            assertThat(result).isEqualTo(batch);
            verify(batch, never()).markAsNotCompleted();
            verify(batchRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("markBatchHasErrors()")
    class MarkBatchHasErrors {

        @Test
        @DisplayName("should mark batch as having errors when not already marked")
        void shouldMarkBatchHasErrors() {
            Batch batch = mock(Batch.class);
            when(batch.getHasErrors()).thenReturn(false);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.save(batch)).thenReturn(batch);

            service.markBatchHasErrors(batchId);

            verify(batch).markAsHavingErrors();
            verify(batchRepository).save(batch);
        }

        @Test
        @DisplayName("should skip when batch already marked as having errors")
        void shouldSkipWhenAlreadyMarked() {
            Batch batch = mock(Batch.class);
            when(batch.getHasErrors()).thenReturn(true);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

            service.markBatchHasErrors(batchId);

            verify(batch, never()).markAsHavingErrors();
            verify(batchRepository, never()).save(any());
        }
    }
}
