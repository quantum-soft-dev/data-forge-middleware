package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.application.ClientApiVersionGuard.HttpFileApiDisabledException;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T7.1 — per-site strangler rule: a site flagged {@code client_api_version = V2} may no longer
 * use the HTTP file API (batch start, CSV upload, schema upload); V1 sites are unaffected.
 * Unknown sites pass through — authentication owns that failure mode.
 */
class ClientApiVersionGuardTest {

    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final ClientApiVersionGuard guard = new ClientApiVersionGuard(siteRepository);

    private final UUID siteId = UUID.randomUUID();

    @Test
    void shouldAllowHttpFileApiWhenSiteIsV1() {
        Site site = siteWithDeltaV2(false);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

        assertDoesNotThrow(() -> guard.assertHttpFileApiAllowed(siteId));
    }

    @Test
    void shouldRejectHttpFileApiWhenSiteIsV2() {
        Site site = siteWithDeltaV2(true);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

        assertThrows(HttpFileApiDisabledException.class, () -> guard.assertHttpFileApiAllowed(siteId));
    }

    @Test
    void shouldAllowHttpFileApiWhenSiteUnknown() {
        when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> guard.assertHttpFileApiAllowed(siteId));
    }

    private static Site siteWithDeltaV2(boolean deltaV2) {
        Site site = mock(Site.class);
        when(site.isDeltaV2()).thenReturn(deltaV2);
        return site;
    }
}
