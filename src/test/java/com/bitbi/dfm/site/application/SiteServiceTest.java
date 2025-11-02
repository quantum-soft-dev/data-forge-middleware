package com.bitbi.dfm.site.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
    private BatchRepository batchRepository;

    @Mock
    private ErrorLogRepository errorLogRepository;

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private S3FileStorageService s3FileStorageService;

    private SiteService siteService;

    private UUID accountId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        siteService = new SiteService(siteRepository, batchRepository, errorLogRepository,
                                      uploadedFileRepository, s3FileStorageService);
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
    @DisplayName("deleteSite - Should hard delete site with cascade deletion of all related data")
    void deleteSite_ShouldHardDeleteWithCascade() {
        // Given
        Site mockSite = mock(Site.class);
        when(mockSite.getDomain()).thenReturn("test.example.com");
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));

        // Mock batches
        Page<Batch> emptyBatchPage = new PageImpl<>(List.of());
        when(batchRepository.findBySiteId(siteId, Pageable.unpaged())).thenReturn(emptyBatchPage);

        // Mock error logs
        when(errorLogRepository.findBySiteId(siteId)).thenReturn(List.of());

        // When
        siteService.deleteSite(siteId);

        // Then
        verify(siteRepository).findById(siteId);
        verify(batchRepository).findBySiteId(siteId, Pageable.unpaged());
        verify(errorLogRepository).findBySiteId(siteId);
        verify(siteRepository).deleteById(siteId);
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
}
