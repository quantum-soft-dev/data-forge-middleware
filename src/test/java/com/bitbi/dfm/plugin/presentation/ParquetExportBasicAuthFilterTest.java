package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ParquetExportBasicAuthFilter} (028-parquet-export-plugin, T007).
 */
@ExtendWith(MockitoExtension.class)
class ParquetExportBasicAuthFilterTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String FILES_PATH = "/api/v1/plugins/parquet-export/files";

    @Mock
    private ParquetExportCredentialsService credentialsService;

    @Mock
    private FilterChain filterChain;

    private ParquetExportBasicAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ParquetExportBasicAuthFilter(credentialsService, new ObjectMapper());
        request = new MockHttpServletRequest("GET", FILES_PATH);
        request.setRequestURI(FILES_PATH);
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String basic(String userinfo) {
        return "Basic " + Base64.getEncoder().encodeToString(userinfo.getBytes(StandardCharsets.UTF_8));
    }

    private AccountPlugin activation() {
        AccountPlugin accountPlugin = AccountPlugin.activate(ACCOUNT_ID, "parquet-export",
                Map.of("login", "pex_login0000001"));
        return accountPlugin;
    }

    @Test
    @DisplayName("Should authenticate valid Basic credentials")
    void shouldAuthenticateValidCredentials() throws Exception {
        AccountPlugin accountPlugin = activation();
        when(credentialsService.validate("pex_login0000001", "secretPassword"))
                .thenReturn(Optional.of(accountPlugin));
        request.addHeader("Authorization", basic("pex_login0000001:secretPassword"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals(ACCOUNT_ID, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLUGIN_CLIENT")));
    }

    @Test
    @DisplayName("Should return 401 with WWW-Authenticate when header is missing")
    void shouldRejectMissingHeader() throws Exception {
        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
        assertEquals("Basic realm=\"parquet-export\"", response.getHeader("WWW-Authenticate"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should return 401 for non-Basic authorization scheme")
    void shouldRejectNonBasicScheme() throws Exception {
        request.addHeader("Authorization", "Bearer some.jwt.token");

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Should return 401 for malformed base64")
    void shouldRejectMalformedBase64() throws Exception {
        request.addHeader("Authorization", "Basic !!!not-base64!!!");

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Should return 401 when userinfo has no colon")
    void shouldRejectMissingColon() throws Exception {
        request.addHeader("Authorization", basic("no-colon-here"));

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Should return 401 for wrong credentials")
    void shouldRejectWrongCredentials() throws Exception {
        when(credentialsService.validate("pex_login0000001", "wrong"))
                .thenReturn(Optional.empty());
        request.addHeader("Authorization", basic("pex_login0000001:wrong"));

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
        assertEquals("Basic realm=\"parquet-export\"", response.getHeader("WWW-Authenticate"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should skip download path (token is the credential there)")
    void shouldSkipDownloadPath() throws Exception {
        MockHttpServletRequest downloadRequest =
                new MockHttpServletRequest("GET", "/api/v1/plugins/parquet-export/download/someToken");
        downloadRequest.setRequestURI("/api/v1/plugins/parquet-export/download/someToken");

        filter.doFilter(downloadRequest, response, filterChain);

        verify(filterChain).doFilter(downloadRequest, response);
        verifyNoInteractions(credentialsService);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should support colon inside the password")
    void shouldSupportColonInPassword() throws Exception {
        AccountPlugin accountPlugin = activation();
        when(credentialsService.validate("pex_login0000001", "pass:with:colons"))
                .thenReturn(Optional.of(accountPlugin));
        request.addHeader("Authorization", basic("pex_login0000001:pass:with:colons"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
