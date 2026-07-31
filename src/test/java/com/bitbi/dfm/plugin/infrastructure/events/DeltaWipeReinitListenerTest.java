package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginDeltaBaselineService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Bit BI auto-reinit after a site history wipe (035 — issue #89).
 *
 * <p>The recapture hangs off the first checkpoint built after the wipe, not off the FULL_SNAPSHOT
 * commit: at commit time every checkpoint of the site has just been deleted in the same
 * transaction, so a recapture there would freeze baseline 0 and the following DELTA segments would
 * generate SQL overlapping the checkpoint CSVs the plugin client downloads.</p>
 */
@DisplayName("DeltaWipeReinitListener")
class DeltaWipeReinitListenerTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final SiteService siteService = mock(SiteService.class);
    private final AccountPluginRepository accountPluginRepository = mock(AccountPluginRepository.class);
    private final PluginDeltaBaselineService baselineService = mock(PluginDeltaBaselineService.class);
    private final PluginAuditService auditService = mock(PluginAuditService.class);

    private DeltaWipeReinitListener listener;
    private Site site;
    private AccountPlugin activation;

    @BeforeEach
    void setUp() {
        listener = new DeltaWipeReinitListener(syncStateService, siteService, accountPluginRepository,
                baselineService, auditService);

        site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);
        when(siteService.getSite(SITE_ID)).thenReturn(site);

        activation = mock(AccountPlugin.class);
        when(activation.isActive()).thenReturn(true);
    }

    @Test
    @DisplayName("recaptures the site's baselines on the first checkpoint after a wipe")
    void shouldRecaptureAfterWipe() {
        when(syncStateService.consumeWipePending(SITE_ID)).thenReturn(true);
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(activation));

        listener.onCheckpointRecorded(new CheckpointRecordedEvent(SITE_ID, 42L));

        verify(baselineService).recaptureForSite(activation, site);
        verify(auditService).logDeltaAutoReinit(eq(ACCOUNT_ID), eq(SITE_ID), anyLong());
    }

    @Test
    @DisplayName("does nothing on an ordinary checkpoint")
    void shouldIgnoreOrdinaryCheckpoint() {
        // Every checkpoint build publishes this event; only the ones that consume a pending wipe
        // may act, otherwise a re-baseline would silently start auto-reinitializing too.
        when(syncStateService.consumeWipePending(SITE_ID)).thenReturn(false);

        listener.onCheckpointRecorded(new CheckpointRecordedEvent(SITE_ID, 42L));

        verifyNoInteractions(baselineService, auditService, accountPluginRepository);
    }

    @Test
    @DisplayName("clears the flag even when the account has no bit-bi activation")
    void shouldClearFlagWithoutActivation() {
        when(syncStateService.consumeWipePending(SITE_ID)).thenReturn(true);
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.empty());

        listener.onCheckpointRecorded(new CheckpointRecordedEvent(SITE_ID, 42L));

        // A later activation captures baselines for itself, so leaving the flag raised would only
        // arm a recapture for an unrelated future wipe.
        verify(baselineService, never()).recaptureForSite(any(), any());
        verify(auditService, never()).logDeltaAutoReinit(any(), any(), anyLong());
    }

    @Test
    @DisplayName("skips a deactivated activation")
    void shouldSkipInactiveActivation() {
        when(syncStateService.consumeWipePending(SITE_ID)).thenReturn(true);
        AccountPlugin inactive = mock(AccountPlugin.class);
        when(inactive.isActive()).thenReturn(false);
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(inactive));

        listener.onCheckpointRecorded(new CheckpointRecordedEvent(SITE_ID, 42L));

        verify(baselineService, never()).recaptureForSite(any(), any());
    }

    @Test
    @DisplayName("a failing recapture propagates so its transaction rolls the flag back")
    void shouldPropagateRecaptureFailure() {
        when(syncStateService.consumeWipePending(SITE_ID)).thenReturn(true);
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(activation));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(baselineService).recaptureForSite(activation, site);

        // Taking the flag and recapturing are one transaction: a failure must undo both, so the
        // next checkpoint build tries again rather than leaving the plugin silently un-reinitialized.
        // CheckpointService keeps the exception away from the build itself.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> listener.onCheckpointRecorded(new CheckpointRecordedEvent(SITE_ID, 42L)))
                .isInstanceOf(IllegalStateException.class);

        verify(auditService, never()).logDeltaAutoReinit(any(), any(), anyLong());
    }
}
