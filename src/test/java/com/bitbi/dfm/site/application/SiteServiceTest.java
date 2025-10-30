package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * - listAccountSites: Retrieve active sites sorted by creation date
 * - deactivateSite: Soft delete via isActive flag
 * - reactivateSite (activateSite): Re-enable deactivated site
 * - deleteSite: Permanent soft delete
 * <p>
 * Feature: 007-adding-a-site (T017)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteService")
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @InjectMocks
    private SiteService siteService;

    private UUID accountId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
    }

    @Test
    @DisplayName("listAccountSites - Should return active sites sorted by creation date desc")
    void listAccountSites_ShouldReturnActiveSitesSortedByCreationDate() {
        // Given
        Site site1 = mock(Site.class);
        Site site2 = mock(Site.class);
        List<Site> expectedSites = List.of(site2, site1); // Newest first

        when(siteRepository.findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(accountId))
                .thenReturn(expectedSites);

        // When
        List<Site> result = siteService.listAccountSites(accountId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(site2, site1);
        verify(siteRepository).findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(accountId);
    }

    @Test
    @DisplayName("listAccountSites - Should return empty list when no active sites exist")
    void listAccountSites_ShouldReturnEmptyListWhenNoActiveSites() {
        // Given
        when(siteRepository.findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(accountId))
                .thenReturn(List.of());

        // When
        List<Site> result = siteService.listAccountSites(accountId);

        // Then
        assertThat(result).isEmpty();
        verify(siteRepository).findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc(accountId);
    }

    @Test
    @DisplayName("deactivateSite - Should deactivate active site")
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
    @DisplayName("deleteSite - Should soft delete site via deactivation")
    void deleteSite_ShouldSoftDeleteViaDeactivation() {
        // Given
        Site mockSite = mock(Site.class);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
        when(mockSite.getIsActive()).thenReturn(true);

        // When
        siteService.deleteSite(siteId);

        // Then
        verify(siteRepository).findById(siteId);
        verify(mockSite).deactivate();
        verify(siteRepository).save(mockSite);
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
        verify(siteRepository, never()).save(any());
    }
}
