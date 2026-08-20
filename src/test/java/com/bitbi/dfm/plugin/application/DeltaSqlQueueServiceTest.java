package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        queueService = new DeltaSqlQueueService(
                segmentRepository, siteRepository, accountPluginRepository,
                sqlGenerationService, baselineRepository, pluginAuditService,
                meterRegistry, 60, 7);

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
        assertThat(segment.getPluginSqlAt()).isNotNull();
        verify(segmentRepository).save(segment);
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
        assertThat(segment.getPluginSqlAt()).isNotNull();
        verify(segmentRepository).save(segment);
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
        assertThat(segment.getPluginSqlAt()).isNotNull();
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
        assertThat(second.getPluginSqlAt()).isNotNull();
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

        assertThat(queueService.processNextPending())
                .as("the drain keeps going, on to another site's head")
                .isTrue();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(segmentRepository).deferPluginSql(eq(segment.getId()), retryAt.capture());
        assertThat(retryAt.getValue()).isAfter(before.plusSeconds(59));
        assertThat(segment.getPluginSqlAt()).as("still the durable queue entry").isNull();
        verify(segmentRepository, never()).save(segment);
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

        assertThat(queueService.processNextPending()).isTrue();

        assertThat(meterRegistry.counter("sql.generation.delta.segments.poisoned").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("sql.generation.delta.segments.deferred").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("both queue series exist from startup, so an alert can predate the first failure")
    void shouldRegisterTheQueueSeriesAtZero() {
        assertThat(meterRegistry.find("sql.generation.delta.segments.deferred").counter()).isNotNull();
        assertThat(meterRegistry.find("sql.generation.delta.segments.poisoned").counter()).isNotNull();
    }
}
