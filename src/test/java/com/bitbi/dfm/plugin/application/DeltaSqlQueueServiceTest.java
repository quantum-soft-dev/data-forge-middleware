package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeltaSqlQueueService} (026-bitbi-delta-sql, T6).
 */
@DisplayName("DeltaSqlQueueService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeltaSqlQueueServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Long ACTIVATION_ID = 5L;

    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private AccountPluginRepository accountPluginRepository;
    @Mock
    private SqlGenerationService sqlGenerationService;
    @Mock
    private PluginDeltaBaselineRepository baselineRepository;
    @Mock
    private PluginAuditService pluginAuditService;

    private DeltaSqlQueueService queueService;

    private Site site;
    private AccountPlugin activation;
    private SimpleMeterRegistry meterRegistry;
    private ApplicationShutdownSignal shutdownSignal;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        shutdownSignal = new ApplicationShutdownSignal();
        queueService = new DeltaSqlQueueService(
                segmentRepository, siteRepository, accountPluginRepository,
                sqlGenerationService, baselineRepository, pluginAuditService,
                meterRegistry, 60, 7, shutdownSignal);

        site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);

        activation = mock(AccountPlugin.class);
        when(activation.getId()).thenReturn(ACTIVATION_ID);
        when(activation.isActive()).thenReturn(true);

        when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(site));
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(activation));
    }

    private ChangelogSegment segment(String mode, Map<String, TableChangeStats> stats) {
        return ChangelogSegment.create(SITE_ID, BATCH_ID, 1L, 9L, 9L, "hash", "delta/x", mode, stats);
    }

    @Test
    @DisplayName("should generate SQL and mark the claimed segment processed")
    void shouldGenerateAndMark() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));

        boolean processed = queueService.processNextPending();

        assertThat(processed).isTrue();
        verify(sqlGenerationService).generateSqlForBatch(BATCH_ID, ACTIVATION_ID);
        verify(segmentRepository).markPluginSqlProcessed(segment.getId());
        verify(segmentRepository, never()).save(segment);
    }

    @Test
    @DisplayName("should return false when the queue is empty")
    void shouldReturnFalseWhenQueueEmpty() {
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of());

        assertThat(queueService.processNextPending()).isFalse();
        verifyNoInteractions(sqlGenerationService);
    }

    @Test
    @DisplayName("should mark-skip segments when the plugin is inactive or absent")
    void shouldMarkSkipWhenPluginInactive() {
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.empty());
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));

        boolean processed = queueService.processNextPending();

        assertThat(processed).isTrue();
        verifyNoInteractions(sqlGenerationService);
        verify(segmentRepository).markPluginSqlProcessed(segment.getId());
        verify(segmentRepository, never()).save(segment);
    }

    @Test
    @DisplayName("FULL_SNAPSHOT: should suspend baselines, audit-warn, emit no SQL")
    void shouldSuspendOnFullSnapshot() {
        ChangelogSegment segment = segment("FULL_SNAPSHOT",
                Map.of("customers", new TableChangeStats(10, 0, 0)));
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));

        PluginDeltaBaseline existing = PluginDeltaBaseline.create(ACTIVATION_ID, SITE_ID, "orders", 3L);
        when(baselineRepository.findByAccountPluginIdAndSiteId(ACTIVATION_ID, SITE_ID))
                .thenReturn(List.of(existing));

        boolean processed = queueService.processNextPending();

        assertThat(processed).isTrue();
        verifyNoInteractions(sqlGenerationService);
        // existing row suspended
        assertThat(existing.getBaselineSeq()).isEqualTo(Long.MAX_VALUE);
        // snapshot table without a row gets a MAX_VALUE row
        ArgumentCaptor<PluginDeltaBaseline> captor = ArgumentCaptor.forClass(PluginDeltaBaseline.class);
        verify(baselineRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(b -> "customers".equals(b.getTableName()) && b.getBaselineSeq() == Long.MAX_VALUE);
        // audit warning signals reinit required
        verify(pluginAuditService).logSqlGenerationFailed(
                eq("bit-bi"), eq(ACCOUNT_ID), eq(BATCH_ID), eq(SITE_ID), anyString(), anyLong());
        verify(segmentRepository).markPluginSqlProcessed(segment.getId());
        verify(segmentRepository, never()).save(segment);
    }

    @Test
    @DisplayName("FULL_SNAPSHOT: a segmented snapshot signals reinit once, not once per segment")
    void shouldSignalRebaselineOncePerSnapshotNotPerSegment() {
        // 033: a re-baseline larger than the session buffer now arrives as N FULL_SNAPSHOT segments
        // published together. Suspension is already idempotent per table, but the audit entry and
        // the rebaseline metric must not fire N times — the owner is told to reinit once.
        PluginDeltaBaseline existing = PluginDeltaBaseline.create(ACTIVATION_ID, SITE_ID, "customers", 3L);
        when(baselineRepository.findByAccountPluginIdAndSiteId(ACTIVATION_ID, SITE_ID))
                .thenReturn(List.of(existing));

        ChangelogSegment first = segment("FULL_SNAPSHOT", Map.of("customers", new TableChangeStats(10, 0, 0)));
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(first));
        assertThat(queueService.processNextPending()).isTrue();

        ChangelogSegment second = segment("FULL_SNAPSHOT", Map.of("customers", new TableChangeStats(7, 0, 0)));
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(second));
        assertThat(queueService.processNextPending()).isTrue();

        assertThat(existing.getBaselineSeq()).isEqualTo(Long.MAX_VALUE);
        verify(pluginAuditService, times(1)).logSqlGenerationFailed(
                eq("bit-bi"), eq(ACCOUNT_ID), any(), eq(SITE_ID), anyString(), anyLong());
        assertThat(meterRegistry.counter("sql.generation.delta.rebaseline.detected").count()).isEqualTo(1.0);
        // Both segments are still consumed — the queue must not stall on the quiet one.
        verify(segmentRepository).markPluginSqlProcessed(first.getId());
        verify(segmentRepository).markPluginSqlProcessed(second.getId());
        verify(segmentRepository, never()).save(any());
        verifyNoInteractions(sqlGenerationService);
    }

    @Test
    @DisplayName("FULL_SNAPSHOT: a later segment still suspends a table the earlier ones never carried")
    void shouldSuspendTablesFirstSeenInALaterSegment() {
        // Tables are spread across a snapshot's segments; one appearing only in the tail must still
        // be suspended, and that is a real change, so it signals again.
        when(baselineRepository.findByAccountPluginIdAndSiteId(ACTIVATION_ID, SITE_ID))
                .thenReturn(List.of());

        ChangelogSegment first = segment("FULL_SNAPSHOT", Map.of("customers", new TableChangeStats(10, 0, 0)));
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(first));
        queueService.processNextPending();

        ChangelogSegment second = segment("FULL_SNAPSHOT", Map.of("orders", new TableChangeStats(4, 0, 0)));
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(second));
        queueService.processNextPending();

        ArgumentCaptor<PluginDeltaBaseline> captor = ArgumentCaptor.forClass(PluginDeltaBaseline.class);
        verify(baselineRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(b -> "orders".equals(b.getTableName()) && b.getBaselineSeq() == Long.MAX_VALUE);
    }

    @Test
    @DisplayName("should defer a failing segment instead of ending the drain on it (#243)")
    void shouldDeferOnFailure() {
        // Before #243 this threw out of DeltaSqlSweepWorker.drain, and since the claim is the
        // globally oldest per-site head with LIMIT 1, the same segment was offered first on every
        // wake — one poison batch and no other site's SQL was ever generated.
        ChangelogSegment segment = segment("DELTA", Map.of());
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(segmentRepository.deferPluginSql(any(), any(), anyInt())).thenReturn(1);

        assertThat(queueService.processNextPending())
                .as("this drain stops after one deferral; the next wake claims another site's head, "
                        + "which is what keeps a systemic failure from walking the whole backlog")
                .isFalse();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(segmentRepository).deferPluginSql(eq(segment.getId()), retryAt.capture(), eq(0));
        assertThat(retryAt.getValue()).isAfter(before.plusSeconds(59));
        assertThat(segment.getPluginSqlAt()).as("still the durable queue entry").isNull();
        verify(segmentRepository, never()).save(segment);
        verify(segmentRepository, never()).markPluginSqlProcessed(any());
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isZero();
    }

    @Test
    @DisplayName("should report a segment poisoned once it passes the attempt threshold (#243)")
    void shouldReportPoisonedPastTheThreshold() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        ReflectionTestUtils.setField(segment, "pluginSqlAttempts", 6);
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(segmentRepository.deferPluginSql(any(), any(), anyInt())).thenReturn(1);

        assertThat(queueService.processNextPending()).isFalse();

        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a segment whose site row is gone is deferred, not left to stall the queue (#243 r2)")
    void shouldDeferWhenTheSiteLookupFails() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(siteRepository.findById(SITE_ID)).thenReturn(Optional.empty());
        when(segmentRepository.deferPluginSql(any(), any(), anyInt())).thenReturn(1);

        assertThat(queueService.processNextPending()).isFalse();

        verify(segmentRepository).deferPluginSql(eq(segment.getId()), any(), anyInt());
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failure while the pod is shutting down spends no attempt (#162's rule)")
    void shouldNotSpendAnAttemptWhenThePodIsShuttingDown() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new RuntimeException("data source is closed"));
        shutdownSignal.onApplicationEvent(null);

        assertThat(queueService.processNextPending()).isFalse();

        verify(segmentRepository, never()).deferPluginSql(any(), any(), anyInt());
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isZero();
    }

    @Test
    @DisplayName("a deferral that matched no row is not reported (#243, review round 1)")
    void shouldNotReportADeferralThatMatchedNoRow() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(segmentRepository.deferPluginSql(any(), any(), anyInt())).thenReturn(0);

        assertThat(queueService.processNextPending()).isFalse();

        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isZero();
        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isZero();
    }

    @Test
    @DisplayName("the #164 guard is loud rather than read as this segment's failure")
    void shouldRefuseToDrainInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> queueService.processNextPending())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("#164");
            verify(segmentRepository, never()).deferPluginSql(any(), any(), anyInt());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("both queue series exist from startup, so an alert can predate the first failure")
    void shouldRegisterTheQueueSeriesAtZero() {
        assertThat(meterRegistry.find("sql.generation.delta.segments.deferred").counter()).isNotNull();
        assertThat(meterRegistry.find("sql.generation.delta.segments.poisoned").counter()).isNotNull();
    }

    @Test
    @DisplayName("a semaphore timeout spends no attempt and is not a verdict on the segment (#261)")
    void shouldNotSpendAnAttemptOnSemaphoreTimeout() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new SqlGenerationService.SemaphoreTimeoutAbortedException(BATCH_ID));

        assertThatThrownBy(() -> queueService.processNextPending())
                .isInstanceOf(SqlGenerationService.SemaphoreTimeoutAbortedException.class)
                .isInstanceOf(SqlGenerationService.PodLevelAbortedException.class);

        assertThat(segment.getPluginSqlAt()).isNull();
        verify(segmentRepository, never()).save(segment);
        verify(segmentRepository, never()).markPluginSqlProcessed(any());
        verify(segmentRepository, never()).deferPluginSql(any(), any(), anyInt());
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isZero();
        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isZero();
    }

    @Test
    @DisplayName("memory-pressure refusal is the same pod-level exemption, by the shared parent (#261)")
    void shouldNotSpendAnAttemptOnMemoryPressure() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new SqlGenerationService.MemoryPressureAbortedException(BATCH_ID));

        assertThatThrownBy(() -> queueService.processNextPending())
                .isInstanceOf(SqlGenerationService.MemoryPressureAbortedException.class)
                .isInstanceOf(SqlGenerationService.PodLevelAbortedException.class);

        verify(segmentRepository, never()).deferPluginSql(any(), any(), anyInt());
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isZero();
        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isZero();
    }

    @Test
    @DisplayName("a wrapped S3 IOException is this segment's failure, not a pod-level refusal (#261)")
    void shouldDeferAWrappedS3FailureAsTheSegmentsOwn() {
        // Decided rather than inherited: a bucket outage and a missing object for this batch
        // arrive as the same wrap, and a missing object should poison. An outage that lasts
        // the doubling window is an incident either way; the per-wake bound plus
        // "many at once means systemic" is how to read it.
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(eq(1), any())).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new SqlGenerationService.SqlGenerationException(
                        "Failed to read files for SQL generation",
                        new IOException("The specified key does not exist")));
        when(segmentRepository.deferPluginSql(any(), any(), anyInt())).thenReturn(1);

        assertThat(queueService.processNextPending()).isFalse();

        verify(segmentRepository).deferPluginSql(eq(segment.getId()), any(), eq(0));
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isZero();
    }
}
