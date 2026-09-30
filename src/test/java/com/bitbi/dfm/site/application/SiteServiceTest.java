package com.bitbi.dfm.site.application;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.delta.application.SiteHistoryPurge;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SiteService application layer.
 * <p>
 * Tests business logic for site management operations:
 * - listAccountSites: Retrieve ALL sites (active and inactive) sorted by creation date
 * - deactivateSite: Soft delete via isActive flag
 * - reactivateSite (activateSite): Re-enable deactivated site
 * - deleteSite: Hard delete with cascade (removes all related data)
 * <p>
 * Feature: 007-adding-a-site (T017)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteService")
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteHistoryPurge historyPurge;

    @Mock
    private DeviceAuthorizationRepository deviceAuthorizationRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private com.bitbi.dfm.site.application.SiteSchemaService siteSchemaService;

    private SiteService siteService;

    private UUID accountId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        siteService = new SiteService(siteRepository, historyPurge, deviceAuthorizationRepository,
                                      refreshTokenService, siteSchemaService);
    }

    @Test
    @DisplayName("listAccountSites - Should return ALL sites (active and inactive) sorted by creation date desc")
    void listAccountSites_ShouldReturnAllSitesSortedByCreationDate() {
        // Given
        Site site1 = mock(Site.class);
        Site site2 = mock(Site.class);
        List<Site> expectedSites = List.of(site2, site1); // Newest first

        when(siteRepository.findByAccountId(accountId))
                .thenReturn(expectedSites);

        // When
        List<Site> result = siteService.listAccountSites(accountId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(site2, site1);
        verify(siteRepository).findByAccountId(accountId);
    }

    @Test
    @DisplayName("listAccountSites - Should return empty list when no sites exist")
    void listAccountSites_ShouldReturnEmptyListWhenNoSites() {
        // Given
        when(siteRepository.findByAccountId(accountId))
                .thenReturn(List.of());

        // When
        List<Site> result = siteService.listAccountSites(accountId);

        // Then
        assertThat(result).isEmpty();
        verify(siteRepository).findByAccountId(accountId);
    }

    @Test
    @DisplayName("deactivateSite - Should deactivate active site and revoke refresh tokens")
    void deactivateSite_ShouldDeactivateActiveSite() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(mockSite.getIsActive()).thenReturn(true);

        // When
        siteService.deactivateSite(siteId);

        // Then
        verify(siteRepository).findById(siteId);
        verify(mockSite).deactivate();
        verify(siteRepository).save(mockSite);
        verify(refreshTokenService).revokeAllForSite(siteId);
    }

    @Test
    @DisplayName("deactivateSite - Should not save when site already deactivated")
    void deactivateSite_ShouldNotSaveWhenAlreadyDeactivated() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(mockSite.getIsActive()).thenReturn(false);

        // When
        siteService.deactivateSite(siteId);

        // Then
        verify(siteRepository).findById(siteId);
        verify(mockSite, never()).deactivate();
        verify(siteRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivateSite - Should throw exception when site not found")
    void deactivateSite_ShouldThrowExceptionWhenSiteNotFound() {
        // Given
        when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> siteService.deactivateSite(siteId))
                .isInstanceOf(SiteService.SiteNotFoundException.class)
                .hasMessageContaining("Site not found");

        verify(siteRepository).findById(siteId);
        verify(siteRepository, never()).save(any());
    }

    @Test
    @DisplayName("reactivateSite - Should activate deactivated site")
    void reactivateSite_ShouldActivateDeactivatedSite() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(mockSite.getIsActive()).thenReturn(false);
        when(siteRepository.save(mockSite)).thenReturn(mockSite);

        // When
        Site result = siteService.reactivateSite(siteId);

        // Then
        assertThat(result).isEqualTo(mockSite);
        verify(siteRepository).findById(siteId);
        verify(mockSite).activate();
        verify(siteRepository).save(mockSite);
    }

    @Test
    @DisplayName("reactivateSite - Should return site when already active")
    void reactivateSite_ShouldReturnSiteWhenAlreadyActive() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(mockSite.getIsActive()).thenReturn(true);

        // When
        Site result = siteService.reactivateSite(siteId);

        // Then
        assertThat(result).isEqualTo(mockSite);
        verify(siteRepository).findById(siteId);
        verify(mockSite, never()).activate();
        verify(siteRepository, never()).save(any());
    }

    @Test
    @DisplayName("reactivateSite - Should throw exception when site not found")
    void reactivateSite_ShouldThrowExceptionWhenSiteNotFound() {
        // Given
        when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> siteService.reactivateSite(siteId))
                .isInstanceOf(SiteService.SiteNotFoundException.class)
                .hasMessageContaining("Site not found");

        verify(siteRepository).findById(siteId);
        verify(siteRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateRetentionDays - Should update site retention policy")
    void updateRetentionDays_ShouldUpdateRetentionPolicy() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(siteRepository.save(mockSite)).thenReturn(mockSite);

        // When
        Site result = siteService.updateRetentionDays(siteId, 45);

        // Then
        assertThat(result).isEqualTo(mockSite);
        verify(siteRepository).findById(siteId);
        verify(mockSite).updateRetentionDays(45);
        verify(siteRepository).save(mockSite);
    }

    @Test
    @DisplayName("deleteSite - purges the history, then the site, and deletes the objects last")
    void deleteSite_PurgesHistoryThenSiteThenObjects() {
        Site mockSite = mock(Site.class);
        when(mockSite.getSiteName()).thenReturn("test.example.com");
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        List<String> keys = List.of("delta/" + siteId + "/segments/a.pb.gz", "acct/site/file.csv");
        when(historyPurge.purgeRows(siteId)).thenReturn(
                new SiteHistoryPurge.PurgedHistory(1, 1, 0, 1, 0, 0, 10L, false, keys));

        siteService.deleteSite(siteId);

        org.mockito.InOrder order = inOrder(historyPurge, deviceAuthorizationRepository, siteSchemaService,
                siteRepository);
        order.verify(historyPurge).purgeRows(siteId);
        order.verify(deviceAuthorizationRepository).deleteBySiteId(siteId);
        order.verify(siteSchemaService).deleteSchema(siteId);
        order.verify(siteRepository).deleteById(siteId);
        order.verify(historyPurge).deleteObjectsOfDeletedSite(siteId, keys);
    }

    @Test
    @DisplayName("deleteSite - a failed purge deletes neither the site nor any object")
    void deleteSite_FailedPurgeDeletesNothingElse() {
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mock(Site.class)));
        when(historyPurge.purgeRows(siteId)).thenThrow(new IllegalStateException("FK"));

        assertThatThrownBy(() -> siteService.deleteSite(siteId)).hasMessage("FK");

        verify(siteRepository, never()).deleteById(any());
        verify(historyPurge, never()).deleteObjectsOfDeletedSite(any(), any());
    }

    @Test
    @DisplayName("deleteSite - Should throw exception when site not found")
    void deleteSite_ShouldThrowExceptionWhenSiteNotFound() {
        // Given
        when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> siteService.deleteSite(siteId))
                .isInstanceOf(SiteService.SiteNotFoundException.class)
                .hasMessageContaining("Site not found");

        verify(siteRepository).findById(siteId);
        verify(siteRepository, never()).deleteById(any());
    }

    // --- getOrCreateSiteWithNewCredentials tests ---

    @Test
    @DisplayName("getOrCreateSiteWithNewCredentials - Should create new site when not exists")
    void getOrCreateSiteWithNewCredentials_ShouldCreateNewSiteWhenNotExists() {
        // Given
        String siteName = "test-site";
        String displayName = "Test Site";

        when(siteRepository.findByAccountIdAndSiteName(accountId, siteName)).thenReturn(Optional.empty());
        when(siteRepository.save(any(Site.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        SiteService.SiteCreationResult result = siteService.getOrCreateSiteWithNewCredentials(
                accountId, siteName, displayName);

        // Then
        assertThat(result.site()).isNotNull();
        // Auth V2: no credentials generated
        assertThat(result.plaintextSecret()).isNull();
        // findByAccountIdAndSiteName is called twice: once in getOrCreateSite, once in createSite
        verify(siteRepository, times(2)).findByAccountIdAndSiteName(accountId, siteName);
        verify(siteRepository).save(any(Site.class));
    }

    @Test
    @DisplayName("getOrCreateSiteWithNewCredentials - Should return existing active site without credential regeneration")
    void getOrCreateSiteWithNewCredentials_ShouldReturnExistingActiveSite() {
        // Given
        String siteName = "existing-site";
        String displayName = "Existing Site";

        Site existingSite = mock(Site.class);
        when(existingSite.getIsActive()).thenReturn(true);

        when(siteRepository.findByAccountIdAndSiteName(accountId, siteName)).thenReturn(Optional.of(existingSite));
        when(siteRepository.save(existingSite)).thenReturn(existingSite);

        // When
        SiteService.SiteCreationResult result = siteService.getOrCreateSiteWithNewCredentials(
                accountId, siteName, displayName);

        // Then
        assertThat(result.site()).isEqualTo(existingSite);
        // Auth V2: no credentials generated
        assertThat(result.plaintextSecret()).isNull();
        verify(existingSite, never()).activate(); // Already active
        verify(siteRepository).save(existingSite);
    }

    @Test
    @DisplayName("getOrCreateSiteWithNewCredentials - Should reactivate deactivated site")
    void getOrCreateSiteWithNewCredentials_ShouldReactivateDeactivatedSite() {
        // Given
        String siteName = "deactivated-site";
        String displayName = "Deactivated Site";

        Site existingSite = mock(Site.class);
        when(existingSite.getIsActive()).thenReturn(false); // Deactivated

        when(siteRepository.findByAccountIdAndSiteName(accountId, siteName)).thenReturn(Optional.of(existingSite));
        when(siteRepository.save(existingSite)).thenReturn(existingSite);

        // When
        SiteService.SiteCreationResult result = siteService.getOrCreateSiteWithNewCredentials(
                accountId, siteName, displayName);

        // Then
        assertThat(result.site()).isEqualTo(existingSite);
        // Auth V2: no credentials generated
        assertThat(result.plaintextSecret()).isNull();
        verify(existingSite).activate();
        verify(siteRepository).save(existingSite);
    }
}
