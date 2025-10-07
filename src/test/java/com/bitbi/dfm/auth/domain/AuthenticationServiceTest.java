package com.bitbi.dfm.auth.domain;

import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthenticationService.
 */
@DisplayName("AuthenticationService Unit Tests")
class AuthenticationServiceTest {

    private AuthenticationService authenticationService;
    private Site mockSite;
    private UUID testAccountId;

    @BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService();
        mockSite = mock(Site.class);
        testAccountId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should validate credentials successfully")
    void shouldValidateCredentialsSuccessfully() {
        // Given
        String domain = "test.example.com";
        String secret = "secret123";

        when(mockSite.canAuthenticate()).thenReturn(true);
        when(mockSite.getDomain()).thenReturn(domain);
        when(mockSite.verifySecret(secret)).thenReturn(true);

        // When
        boolean result = authenticationService.validateCredentials(mockSite, domain, secret, true);

        // Then
        assertTrue(result);
        verify(mockSite, times(1)).canAuthenticate();
        verify(mockSite, times(1)).getDomain();
        verify(mockSite, times(1)).verifySecret(secret);
    }

    @Test
    @DisplayName("Should fail validation when site cannot authenticate")
    void shouldFailValidationWhenSiteCannotAuthenticate() {
        // Given
        String domain = "test.example.com";
        String secret = "secret123";

        when(mockSite.canAuthenticate()).thenReturn(false);

        // When
        boolean result = authenticationService.validateCredentials(mockSite, domain, secret, true);

        // Then
        assertFalse(result);
        verify(mockSite, times(1)).canAuthenticate();
        verify(mockSite, never()).getDomain();
        verify(mockSite, never()).verifySecret(anyString());
    }

    @Test
    @DisplayName("Should fail validation when account is inactive")
    void shouldFailValidationWhenAccountIsInactive() {
        // Given
        String domain = "test.example.com";
        String secret = "secret123";

        when(mockSite.canAuthenticate()).thenReturn(true);

        // When
        boolean result = authenticationService.validateCredentials(mockSite, domain, secret, false);

        // Then
        assertFalse(result);
        verify(mockSite, times(1)).canAuthenticate();
        verify(mockSite, never()).getDomain();
        verify(mockSite, never()).verifySecret(anyString());
    }

    @Test
    @DisplayName("Should fail validation when credentials do not match")
    void shouldFailValidationWhenCredentialsDoNotMatch() {
        // Given
        String domain = "test.example.com";
        String secret = "wrong-secret";

        when(mockSite.canAuthenticate()).thenReturn(true);
        when(mockSite.getDomain()).thenReturn(domain);
        when(mockSite.verifySecret(secret)).thenReturn(false);

        // When
        boolean result = authenticationService.validateCredentials(mockSite, domain, secret, true);

        // Then
        assertFalse(result);
        verify(mockSite, times(1)).canAuthenticate();
        verify(mockSite, times(1)).getDomain();
        verify(mockSite, times(1)).verifySecret(secret);
    }

    @Test
    @DisplayName("Should throw exception when site is null")
    void shouldThrowExceptionWhenSiteIsNull() {
        // Given
        String domain = "test.example.com";
        String secret = "secret123";

        // When & Then
        assertThrows(NullPointerException.class, () ->
                authenticationService.validateCredentials(null, domain, secret, true)
        );
    }

    @Test
    @DisplayName("Should throw exception when provided domain is null")
    void shouldThrowExceptionWhenProvidedDomainIsNull() {
        // Given
        String secret = "secret123";

        // When & Then
        assertThrows(NullPointerException.class, () ->
                authenticationService.validateCredentials(mockSite, null, secret, true)
        );
    }

    @Test
    @DisplayName("Should throw exception when provided secret is null")
    void shouldThrowExceptionWhenProvidedSecretIsNull() {
        // Given
        String domain = "test.example.com";

        // When & Then
        assertThrows(NullPointerException.class, () ->
                authenticationService.validateCredentials(mockSite, domain, null, true)
        );
    }

    @Test
    @DisplayName("Should return generic error when site does not exist")
    void shouldReturnGenericErrorWhenSiteDoesNotExist() {
        // When
        String reason = authenticationService.getAuthenticationFailureReason(false, true, true, true);

        // Then
        assertEquals("Invalid credentials", reason);
    }

    @Test
    @DisplayName("Should return generic error when site is inactive")
    void shouldReturnGenericErrorWhenSiteIsInactive() {
        // When
        String reason = authenticationService.getAuthenticationFailureReason(true, false, true, true);

        // Then
        assertEquals("Invalid credentials", reason);
    }

    @Test
    @DisplayName("Should return generic error when account is inactive")
    void shouldReturnGenericErrorWhenAccountIsInactive() {
        // When
        String reason = authenticationService.getAuthenticationFailureReason(true, true, false, true);

        // Then
        assertEquals("Invalid credentials", reason);
    }

    @Test
    @DisplayName("Should return generic error when credentials do not match")
    void shouldReturnGenericErrorWhenCredentialsDoNotMatch() {
        // When
        String reason = authenticationService.getAuthenticationFailureReason(true, true, true, false);

        // Then
        assertEquals("Invalid credentials", reason);
    }

    @Test
    @DisplayName("Should return success message when all checks pass")
    void shouldReturnSuccessMessageWhenAllChecksPass() {
        // When
        String reason = authenticationService.getAuthenticationFailureReason(true, true, true, true);

        // Then
        assertEquals("Authentication successful", reason);
    }

    @Test
    @DisplayName("Should return generic failure message")
    void shouldReturnGenericFailureMessage() {
        // When
        String message = authenticationService.getGenericFailureMessage();

        // Then
        assertEquals("Invalid credentials", message);
    }

    @Test
    @DisplayName("Should not reveal specific failure reasons for security")
    void shouldNotRevealSpecificFailureReasonsForSecurity() {
        // Test that all failure scenarios return the same generic message
        String siteNotExistsReason = authenticationService.getAuthenticationFailureReason(false, true, true, true);
        String siteInactiveReason = authenticationService.getAuthenticationFailureReason(true, false, true, true);
        String accountInactiveReason = authenticationService.getAuthenticationFailureReason(true, true, false, true);
        String credentialsMismatchReason = authenticationService.getAuthenticationFailureReason(true, true, true, false);

        // All should return the same generic message
        assertEquals(siteNotExistsReason, siteInactiveReason);
        assertEquals(siteInactiveReason, accountInactiveReason);
        assertEquals(accountInactiveReason, credentialsMismatchReason);
        assertEquals("Invalid credentials", siteNotExistsReason);
    }
}
