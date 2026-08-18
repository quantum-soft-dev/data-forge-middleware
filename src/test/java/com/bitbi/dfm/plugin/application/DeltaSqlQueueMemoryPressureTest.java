package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The memory-pressure abort seen from the delta-SQL queue (issue #181).
 *
 * <p>{@link DeltaSqlQueueServiceTest#shouldLeavePendingOnFailure()} already pins the queue's
 * contract for a generation that <em>throws</em>. What it cannot pin — because it mocks
 * {@link SqlGenerationService} — is that the memory-pressure abort <em>is</em> such a failure:
 * before #181 it returned an empty {@code Optional}, indistinguishable from "this batch produced
 * no changes", so the segment was marked processed and the batch's SQL was dropped for good. So
 * this class wires the <em>real</em> service, with only its collaborators mocked, and drives the
 * abort through the heap reading.</p>
 */
@DisplayName("DeltaSqlQueueService — memory-pressure abort")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeltaSqlQueueMemoryPressureTest {

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
    private SqlGenerationPersistence persistence;
    @Mock
    private S3SqlFileStorageService s3SqlFileStorageService;
    @Mock
    private PluginAuditService pluginAuditService;
    @Mock
    private SiteSchemaService siteSchemaService;
    @Mock
    private DeltaSqlGenerationStrategy deltaStrategy;
    @Mock
    private PluginDeltaBaselineRepository baselineRepository;

    private SimpleMeterRegistry meterRegistry;
    private SqlGenerationService sqlGenerationService;
    private DeltaSqlQueueService queueService;
    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        sqlGenerationService = spy(new SqlGenerationService(
                accountPluginRepository,
                persistence,
                s3SqlFileStorageService,
                meterRegistry,
                pluginAuditService,
                mock(DbfSqlGenerationStrategy.class),
                mock(CdcSqlGenerationStrategy.class),
                siteSchemaService,
                deltaStrategy,
                baselineRepository,
                2,
                120,
                80));
        sqlGenerationService.init();

        queueService = new DeltaSqlQueueService(
                segmentRepository, siteRepository, accountPluginRepository,
                sqlGenerationService, baselineRepository, pluginAuditService,
                meterRegistry);

        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);
        when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(site));

        AccountPlugin activation = mock(AccountPlugin.class);
        when(activation.getId()).thenReturn(ACTIVATION_ID);
        when(activation.isActive()).thenReturn(true);
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(activation));
        when(accountPluginRepository.findById(ACTIVATION_ID)).thenReturn(Optional.of(activation));

        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(BATCH_ID);
        when(batch.getSiteId()).thenReturn(SITE_ID);
        when(batch.getAccountId()).thenReturn(ACCOUNT_ID);

        segment = ChangelogSegment.create(SITE_ID, BATCH_ID, 1L, 9L, 9L, "hash", "delta/x", "DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));

        when(persistence.existsByBatchId(BATCH_ID)).thenReturn(true);
        when(persistence.loadBatchData(eq(BATCH_ID), anyBoolean())).thenReturn(
                new SqlGenerationPersistence.BatchData(
                        batch, site, List.of(), Optional.empty(), Map.of(), List.of(segment)));
    }

    @Test
    @DisplayName("should leave the claimed segment pending for the sweep instead of consuming it")
    void shouldLeaveTheSegmentPendingOnMemoryPressure() {
        // Given - the heap reading is above the threshold at the instant the batch is picked up
        doReturn(81).when(sqlGenerationService).getHeapUsagePercent();

        // When
        assertThatThrownBy(() -> queueService.processNextPending())
                .isInstanceOf(SqlGenerationService.MemoryPressureAbortedException.class)
                .hasMessageContaining(BATCH_ID.toString());

        // Then - the segment is not consumed: it is still pending, so the sweep offers it again
        // once the pod's heap recovers. This is the whole of #181 at this level.
        assertThat(segment.getPluginSqlAt()).isNull();
        verify(segmentRepository, never()).save(any());
        // and nothing was generated or written on the way out
        verifyNoInteractions(deltaStrategy);
        verify(s3SqlFileStorageService, never()).storeSqlFile(any(), any(), any());
        verify(persistence, never()).saveGenerationRecord(any(), any(), any(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("should name the dropped batch in the plugin audit log, not only in a counter")
    void shouldAuditTheAbortAgainstTheBatch() {
        // Given
        doReturn(81).when(sqlGenerationService).getHeapUsagePercent();

        // When
        assertThatThrownBy(() -> queueService.processNextPending())
                .isInstanceOf(SqlGenerationService.MemoryPressureAbortedException.class);

        // Then - the counter says a batch somewhere was refused; the audit entry says which one,
        // and it is what the account sees on /api/v1/account/plugins/{pluginId}/logs
        assertThat(meterRegistry.counter("sql.generation.aborted.memory_pressure").count()).isEqualTo(1.0);
        verify(pluginAuditService).logSqlGenerationFailed(
                eq("bit-bi"), eq(ACCOUNT_ID), eq(BATCH_ID), eq(SITE_ID),
                contains("memory pressure"), anyLong());
        // and only that entry: the attempt is re-entered once per queue wake — which is once per
        // completed batch across the fleet, not once per sweep tick — so an announced-but-refused
        // generation would double the rows the account has to read
        verify(pluginAuditService, never()).logSqlGenerationStarted(any(), any(), any(), any());
        // The refusal is counted on its own meter and is self-repairing, so the series that means
        // "generation is broken" does not move. Absent rather than zero: init() registers only
        // sql.generation.aborted.memory_pressure, so this series exists at all only once
        // something increments it.
        assertThat(meterRegistry.find("sql.generation.errors").counter()).isNull();
    }

    @Test
    @DisplayName("should consume the segment as usual when the heap is below the threshold")
    void shouldConsumeTheSegmentWhenHeapIsHealthy() throws Exception {
        // Given - the same wiring, with the only difference being the reading. Without this the
        // test above would pass against a service that refuses everything.
        doReturn(10).when(sqlGenerationService).getHeapUsagePercent();
        when(deltaStrategy.generate(any(), any(), any(), any(), any())).thenReturn(null);

        // When
        boolean processed = queueService.processNextPending();

        // Then
        assertThat(processed).isTrue();
        assertThat(segment.getPluginSqlAt()).isNotNull();
        verify(segmentRepository).save(segment);
        assertThat(meterRegistry.counter("sql.generation.aborted.memory_pressure").count()).isEqualTo(0.0);
    }
}
