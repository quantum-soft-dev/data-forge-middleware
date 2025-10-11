package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.presentation.dto.CreateSiteRequestDto;
import com.bitbi.dfm.site.presentation.dto.SiteCreationResponseDto;
import com.bitbi.dfm.site.presentation.dto.SiteResponseDto;
import com.bitbi.dfm.site.presentation.dto.UpdateSiteRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SiteAdminController.
 */
@DisplayName("SiteAdminController Unit Tests")
class SiteAdminControllerTest {

    private SiteAdminController controller;
    private SiteService siteService;
    private AccountStatisticsService accountStatisticsService;

    private UUID testSiteId;
    private UUID testAccountId;
    private Site testSite;

    @BeforeEach
    void setUp() {
        siteService = mock(SiteService.class);
        accountStatisticsService = mock(AccountStatisticsService.class);
        controller = new SiteAdminController(siteService, accountStatisticsService);

        testSiteId = UUID.randomUUID();
        testAccountId = UUID.randomUUID();

        testSite = Site.createForTesting(testAccountId, "test.example.com", "Test Site");
    }

    @Test
    @DisplayName("Should create site successfully")
    void shouldCreateSiteSuccessfully() {
        // Given
        CreateSiteRequestDto request = new CreateSiteRequestDto("test.example.com", "Test Site");

        SiteService.SiteCreationResult result = new SiteService.SiteCreationResult(testSite, "test-secret-plaintext");
        when(siteService.createSite(testAccountId, "test.example.com", "Test Site"))
                .thenReturn(result);

        // When
        ResponseEntity<SiteCreationResponseDto> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        SiteCreationResponseDto body = response.getBody();
        assertEquals(testSite.getDomain(), body.domain());
        assertEquals(testSite.getDisplayName(), body.name());
        assertNotNull(body.clientSecret());
        assertEquals("test-secret-plaintext", body.clientSecret());
        verify(siteService, times(1)).createSite(testAccountId, "test.example.com", "Test Site");
    }

    // NOTE: Validation tests removed - now handled by @Valid annotation and GlobalExceptionHandler
    // For validation testing, see integration/contract tests instead

    @Test
    @DisplayName("Should get site successfully")
    void shouldGetSiteSuccessfully() {
        // Given
        when(siteService.getSite(testSiteId)).thenReturn(testSite);

        // When
        ResponseEntity<SiteResponseDto> response = controller.getSite(testSiteId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        SiteResponseDto body = response.getBody();
        assertEquals(testSite.getDomain(), body.domain());
        assertEquals(testSite.getDisplayName(), body.name());
        verify(siteService, times(1)).getSite(testSiteId);
    }

    // NOTE: Error handling tests removed - now handled by GlobalExceptionHandler
    // For error response testing, see integration tests instead

    @Test
    @DisplayName("Should list sites by account successfully")
    void shouldListSitesByAccountSuccessfully() {
        // Given
        Site site1 = Site.createForTesting(testAccountId, "site1.example.com", "Site 1");
        Site site2 = Site.createForTesting(testAccountId, "site2.example.com", "Site 2");
        List<Site> sites = Arrays.asList(site1, site2);

        when(siteService.listSitesByAccount(testAccountId)).thenReturn(sites);

        // When
        ResponseEntity<List<SiteResponseDto>> response = controller.listSitesByAccount(testAccountId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        List<SiteResponseDto> siteList = response.getBody();
        assertEquals(2, siteList.size());
        assertEquals("site1.example.com", siteList.get(0).domain());
        assertEquals("site2.example.com", siteList.get(1).domain());
        verify(siteService, times(1)).listSitesByAccount(testAccountId);
    }

    @Test
    @DisplayName("Should update site successfully")
    void shouldUpdateSiteSuccessfully() {
        // Given
        UpdateSiteRequestDto request = new UpdateSiteRequestDto("Updated Site");

        when(siteService.updateSite(testSiteId, "Updated Site")).thenReturn(testSite);

        // When
        ResponseEntity<SiteResponseDto> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(siteService, times(1)).updateSite(testSiteId, "Updated Site");
    }

    @Test
    @DisplayName("Should deactivate site successfully")
    void shouldDeactivateSiteSuccessfully() {
        // Given
        doNothing().when(siteService).deactivateSite(testSiteId);

        // When
        ResponseEntity<Void> response = controller.deactivateSite(testSiteId);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(siteService, times(1)).deactivateSite(testSiteId);
    }
}
