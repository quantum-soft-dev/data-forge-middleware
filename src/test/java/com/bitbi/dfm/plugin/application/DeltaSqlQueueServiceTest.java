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

    @BeforeEach
    void setUp() {
        queueService = new DeltaSqlQueueService(
                segmentRepository, siteRepository, accountPluginRepository,
                sqlGenerationService, baselineRepository, pluginAuditService,
                new SimpleMeterRegistry());

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
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));

        boolean processed = queueService.processNextPending();

        assertThat(processed).isTrue();
        verify(sqlGenerationService).generateSqlForBatch(BATCH_ID, ACTIVATION_ID);
        assertThat(segment.getPluginSqlAt()).isNotNull();
        verify(segmentRepository).save(segment);
    }

    @Test
    @DisplayName("should return false when the queue is empty")
    void shouldReturnFalseWhenQueueEmpty() {
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of());

        assertThat(queueService.processNextPending()).isFalse();
        verifyNoInteractions(sqlGenerationService);
    }

    @Test
    @DisplayName("should mark-skip segments when the plugin is inactive or absent")
    void shouldMarkSkipWhenPluginInactive() {
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.empty());
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));

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
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));

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
    @DisplayName("should leave the segment pending when generation fails")
    void shouldLeavePendingOnFailure() {
        ChangelogSegment segment = segment("DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));
        when(sqlGenerationService.generateSqlForBatch(any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> queueService.processNextPending())
                .isInstanceOf(RuntimeException.class);

        assertThat(segment.getPluginSqlAt()).isNull();
        verify(segmentRepository, never()).save(segment);
    }
}
