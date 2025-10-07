package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
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
        Map<String, String> request = new HashMap<>();
        request.put("domain", "test.example.com");
        request.put("displayName", "Test Site");

        SiteService.SiteCreationResult result = new SiteService.SiteCreationResult(testSite, "test-secret-plaintext");
        when(siteService.createSite(testAccountId, "test.example.com", "Test Site"))
                .thenReturn(result);

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testSite.getDomain(), response.getBody().get("domain"));
        assertEquals(testSite.getDisplayName(), response.getBody().get("displayName"));
        assertTrue(response.getBody().containsKey("clientSecret"));
        assertEquals("test-secret-plaintext", response.getBody().get("clientSecret"));
        verify(siteService, times(1)).createSite(testAccountId, "test.example.com", "Test Site");
    }

    @Test
    @DisplayName("Should return 400 when domain is missing")
    void shouldReturn400WhenDomainIsMissing() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("displayName", "Test Site");

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Domain is required", response.getBody().get("error"));
        verify(siteService, never()).createSite(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when domain is blank")
    void shouldReturn400WhenDomainIsBlank() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("domain", "   ");
        request.put("displayName", "Test Site");

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Domain is required", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 400 when display name is missing")
    void shouldReturn400WhenDisplayNameIsMissing() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("domain", "test.example.com");

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Display name is required", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 409 when site already exists")
    void shouldReturn409WhenSiteAlreadyExists() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("domain", "test.example.com");
        request.put("displayName", "Test Site");

        when(siteService.createSite(testAccountId, "test.example.com", "Test Site"))
                .thenThrow(new SiteService.SiteAlreadyExistsException("Site already exists"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Site already exists", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 400 when site service throws IllegalArgumentException")
    void shouldReturn400WhenIllegalArgumentException() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("domain", "invalid domain");
        request.put("displayName", "Test Site");

        when(siteService.createSite(testAccountId, "invalid domain", "Test Site"))
                .thenThrow(new IllegalArgumentException("Invalid domain format"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid domain format", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 500 when site creation fails unexpectedly")
    void shouldReturn500WhenCreationFailsUnexpectedly() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("domain", "test.example.com");
        request.put("displayName", "Test Site");

        when(siteService.createSite(testAccountId, "test.example.com", "Test Site"))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.createSite(testAccountId, request);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to create site", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should get site successfully")
    void shouldGetSiteSuccessfully() {
        // Given
        when(siteService.getSite(testSiteId)).thenReturn(testSite);

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSite(testSiteId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testSite.getDomain(), response.getBody().get("domain"));
        assertEquals(testSite.getDisplayName(), response.getBody().get("displayName"));
        assertFalse(response.getBody().containsKey("clientSecret"));
        verify(siteService, times(1)).getSite(testSiteId);
    }

    @Test
    @DisplayName("Should return 404 when site not found")
    void shouldReturn404WhenSiteNotFound() {
        // Given
        when(siteService.getSite(testSiteId))
                .thenThrow(new SiteService.SiteNotFoundException("Site not found"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSite(testSiteId);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Site not found", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 500 when get site fails unexpectedly")
    void shouldReturn500WhenGetSiteFailsUnexpectedly() {
        // Given
        when(siteService.getSite(testSiteId))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSite(testSiteId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to retrieve site", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should list sites by account successfully")
    void shouldListSitesByAccountSuccessfully() {
        // Given
        Site site1 = Site.createForTesting(testAccountId, "site1.example.com", "Site 1");
        Site site2 = Site.createForTesting(testAccountId, "site2.example.com", "Site 2");
        List<Site> sites = Arrays.asList(site1, site2);

        when(siteService.listSitesByAccount(testAccountId)).thenReturn(sites);

        // When
        ResponseEntity<?> response = controller.listSitesByAccount(testAccountId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof List);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> siteList = (List<Map<String, Object>>) response.getBody();
        assertEquals(2, siteList.size());
        assertEquals("site1.example.com", siteList.get(0).get("domain"));
        assertEquals("site2.example.com", siteList.get(1).get("domain"));
        verify(siteService, times(1)).listSitesByAccount(testAccountId);
    }

    @Test
    @DisplayName("Should return 500 when list sites fails unexpectedly")
    void shouldReturn500WhenListSitesFailsUnexpectedly() {
        // Given
        when(siteService.listSitesByAccount(testAccountId))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<?> response = controller.listSitesByAccount(testAccountId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        Map<String, Object> errorBody = (Map<String, Object>) response.getBody();
        assertEquals("Failed to list sites", errorBody.get("error"));
    }

    @Test
    @DisplayName("Should update site successfully")
    void shouldUpdateSiteSuccessfully() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("displayName", "Updated Site");

        when(siteService.updateSite(testSiteId, "Updated Site")).thenReturn(testSite);

        // When
        ResponseEntity<Map<String, Object>> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(siteService, times(1)).updateSite(testSiteId, "Updated Site");
    }

    @Test
    @DisplayName("Should return 400 when update display name is missing")
    void shouldReturn400WhenUpdateDisplayNameIsMissing() {
        // Given
        Map<String, String> request = new HashMap<>();

        // When
        ResponseEntity<Map<String, Object>> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Display name is required", response.getBody().get("error"));
        verify(siteService, never()).updateSite(any(), any());
    }

    @Test
    @DisplayName("Should return 404 when updating non-existent site")
    void shouldReturn404WhenUpdatingNonExistentSite() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("displayName", "Updated Site");

        when(siteService.updateSite(testSiteId, "Updated Site"))
                .thenThrow(new SiteService.SiteNotFoundException("Site not found"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Site not found", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 400 when update display name is blank")
    void shouldReturn400WhenUpdateDisplayNameIsBlank() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("displayName", "  ");

        // When
        ResponseEntity<Map<String, Object>> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Display name is required", response.getBody().get("error"));
        verify(siteService, never()).updateSite(any(), any());
    }

    @Test
    @DisplayName("Should return 500 when update site fails unexpectedly")
    void shouldReturn500WhenUpdateSiteFailsUnexpectedly() {
        // Given
        Map<String, String> request = new HashMap<>();
        request.put("displayName", "Updated Site");

        when(siteService.updateSite(testSiteId, "Updated Site"))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.updateSite(testSiteId, request);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to update site", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should deactivate site successfully")
    void shouldDeactivateSiteSuccessfully() {
        // Given
        doNothing().when(siteService).deactivateSite(testSiteId);

        // When
        ResponseEntity<Map<String, Object>> response = controller.deactivateSite(testSiteId);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(siteService, times(1)).deactivateSite(testSiteId);
    }

    @Test
    @DisplayName("Should return 404 when deactivating non-existent site")
    void shouldReturn404WhenDeactivatingNonExistentSite() {
        // Given
        doThrow(new SiteService.SiteNotFoundException("Site not found"))
                .when(siteService).deactivateSite(testSiteId);

        // When
        ResponseEntity<Map<String, Object>> response = controller.deactivateSite(testSiteId);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Site not found", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should return 500 when deactivate site fails unexpectedly")
    void shouldReturn500WhenDeactivateSiteFailsUnexpectedly() {
        // Given
        doThrow(new RuntimeException("Database error"))
                .when(siteService).deactivateSite(testSiteId);

        // When
        ResponseEntity<Map<String, Object>> response = controller.deactivateSite(testSiteId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to deactivate site", response.getBody().get("error"));
    }

    @Test
    @DisplayName("Should get site statistics successfully")
    void shouldGetSiteStatisticsSuccessfully() {
        // Given
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalBatches", 10);
        stats.put("totalErrors", 5);

        when(accountStatisticsService.getSiteStatistics(testSiteId)).thenReturn(stats);

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSiteStatistics(testSiteId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().get("totalBatches"));
        assertEquals(5, response.getBody().get("totalErrors"));
        verify(accountStatisticsService, times(1)).getSiteStatistics(testSiteId);
    }

    @Test
    @DisplayName("Should return 500 when get site statistics fails unexpectedly")
    void shouldReturn500WhenGetSiteStatisticsFailsUnexpectedly() {
        // Given
        when(accountStatisticsService.getSiteStatistics(testSiteId))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSiteStatistics(testSiteId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to retrieve statistics", response.getBody().get("error"));
    }
}
