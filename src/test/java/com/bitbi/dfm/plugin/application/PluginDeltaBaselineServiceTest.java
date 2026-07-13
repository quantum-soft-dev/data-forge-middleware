package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PluginDeltaBaselineService} (026-bitbi-delta-sql, T7).
 */
@DisplayName("PluginDeltaBaselineService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginDeltaBaselineServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID V2_SITE_ID = UUID.randomUUID();
    private static final UUID V1_SITE_ID = UUID.randomUUID();
    private static final Long ACTIVATION_ID = 3L;

    @Mock
    private SiteRepository siteRepository;
    @Mock
    private CheckpointRepository checkpointRepository;
    @Mock
    private PluginDeltaBaselineRepository baselineRepository;
    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private DeltaSqlSweepWorker sweepWorker;

    private PluginDeltaBaselineService service;
    private AccountPlugin activation;

    @BeforeEach
    void setUp() {
        service = new PluginDeltaBaselineService(
                siteRepository, checkpointRepository, baselineRepository, segmentRepository, sweepWorker);

        activation = mock(AccountPlugin.class);
        when(activation.getId()).thenReturn(ACTIVATION_ID);
        when(activation.getAccountId()).thenReturn(ACCOUNT_ID);

        Site v2Site = mock(Site.class);
        when(v2Site.getId()).thenReturn(V2_SITE_ID);
        when(v2Site.isDeltaV2()).thenReturn(true);
        Site v1Site = mock(Site.class);
        when(v1Site.getId()).thenReturn(V1_SITE_ID);
        when(v1Site.isDeltaV2()).thenReturn(false);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(v1Site, v2Site));
    }

    @Test
    @DisplayName("should capture per-table baselines from current checkpoints for V2 sites only")
    void shouldCaptureBaselinesForV2Sites() {
        Checkpoint customers = Checkpoint.create(V2_SITE_ID, "customers", 42L, 100L);
        Checkpoint orders = Checkpoint.create(V2_SITE_ID, "orders", 7L, 10L);
        when(checkpointRepository.findBySiteId(V2_SITE_ID)).thenReturn(List.of(customers, orders));

        service.captureBaselines(activation);

        verify(baselineRepository).deleteByAccountPluginIdAndSiteId(ACTIVATION_ID, V2_SITE_ID);
        ArgumentCaptor<PluginDeltaBaseline> captor = ArgumentCaptor.forClass(PluginDeltaBaseline.class);
        verify(baselineRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(b -> "customers".equals(b.getTableName()) && b.getBaselineSeq() == 42L)
                .anyMatch(b -> "orders".equals(b.getTableName()) && b.getBaselineSeq() == 7L);
        // V1 site untouched
        verify(baselineRepository, never()).deleteByAccountPluginIdAndSiteId(ACTIVATION_ID, V1_SITE_ID);
        verify(checkpointRepository, never()).findBySiteId(V1_SITE_ID);
    }

    @Test
    @DisplayName("should leave no rows for a V2 site without checkpoints (default-0 semantics)")
    void shouldLeaveNoRowsWithoutCheckpoints() {
        when(checkpointRepository.findBySiteId(V2_SITE_ID)).thenReturn(List.of());

        service.captureBaselines(activation);

        verify(baselineRepository).deleteByAccountPluginIdAndSiteId(ACTIVATION_ID, V2_SITE_ID);
        verify(baselineRepository, never()).save(any());
    }

    @Test
    @DisplayName("should be a no-op for accounts without V2 sites")
    void shouldNoOpWithoutV2Sites() {
        Site v1Only = mock(Site.class);
        when(v1Only.isDeltaV2()).thenReturn(false);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(v1Only));

        service.captureBaselines(activation);

        verifyNoInteractions(baselineRepository, checkpointRepository, segmentRepository);
    }

    @Test
    @DisplayName("reinit: should recapture, re-enqueue the site's segments and wake the worker")
    void reinitShouldRecaptureAndRequeue() {
        when(checkpointRepository.findBySiteId(V2_SITE_ID)).thenReturn(
                List.of(Checkpoint.create(V2_SITE_ID, "customers", 100L, 5L)));

        service.recaptureForReinit(activation);

        verify(baselineRepository).deleteByAccountPluginIdAndSiteId(ACTIVATION_ID, V2_SITE_ID);
        verify(baselineRepository).save(any(PluginDeltaBaseline.class));
        verify(segmentRepository).clearPluginSqlBySiteId(V2_SITE_ID);
        // no transaction in unit tests → immediate wake
        verify(sweepWorker).wake();
    }

    @Test
    @DisplayName("reinit: should not wake the worker when the account has no V2 sites")
    void reinitShouldNotWakeWithoutV2Sites() {
        Site v1Only = mock(Site.class);
        when(v1Only.isDeltaV2()).thenReturn(false);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(v1Only));

        service.recaptureForReinit(activation);

        verifyNoInteractions(sweepWorker, segmentRepository);
    }
}
