package com.bitbi.dfm.auth.application;

import com.bitbi.dfm.auth.config.Auth0Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Auth0TokenProvider configuration.
 *
 * Tests focus on the configurable token expiry buffer functionality.
 * Auth0 API calls are not tested here (covered by integration tests).
 */
class Auth0TokenProviderTest {

    @Nested
    @DisplayName("Token Expiry Buffer Configuration")
    class TokenExpiryBufferTests {

        @Test
        @DisplayName("should use default buffer of 3600 seconds when not configured")
        void shouldUseDefaultBufferWhenNotConfigured() {
            // Given
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    null  // tokenExpiryBufferSeconds not set
            );

            // When
            long buffer = management.getTokenExpiryBufferSeconds();

            // Then
            assertThat(buffer).isEqualTo(3600L);
        }

        @Test
        @DisplayName("should use configured buffer when explicitly set")
        void shouldUseConfiguredBuffer() {
            // Given
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    7200L  // 2 hours
            );

            // When
            long buffer = management.getTokenExpiryBufferSeconds();

            // Then
            assertThat(buffer).isEqualTo(7200L);
        }

        @Test
        @DisplayName("should allow zero buffer (edge case)")
        void shouldAllowZeroBuffer() {
            // Given
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    0L
            );

            // When
            long buffer = management.getTokenExpiryBufferSeconds();

            // Then
            assertThat(buffer).isEqualTo(0L);
        }

        @Test
        @DisplayName("should allow small buffer for testing")
        void shouldAllowSmallBufferForTesting() {
            // Given - 5 minutes buffer (old default)
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    300L
            );

            // When
            long buffer = management.getTokenExpiryBufferSeconds();

            // Then
            assertThat(buffer).isEqualTo(300L);
        }

        @Test
        @DisplayName("should allow large buffer for high-latency environments")
        void shouldAllowLargeBuffer() {
            // Given - 6 hours buffer
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    21600L
            );

            // When
            long buffer = management.getTokenExpiryBufferSeconds();

            // Then
            assertThat(buffer).isEqualTo(21600L);
        }
    }

    @Nested
    @DisplayName("Auth0Properties.Management record")
    class ManagementRecordTests {

        @Test
        @DisplayName("should expose all fields via record accessors")
        void shouldExposeAllFields() {
            // Given
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "my-client-id",
                    "my-client-secret",
                    "https://my-domain.auth0.com/api/v2/",
                    1800L
            );

            // Then
            assertThat(management.clientId()).isEqualTo("my-client-id");
            assertThat(management.clientSecret()).isEqualTo("my-client-secret");
            assertThat(management.audience()).isEqualTo("https://my-domain.auth0.com/api/v2/");
            assertThat(management.tokenExpiryBufferSeconds()).isEqualTo(1800L);
            assertThat(management.getTokenExpiryBufferSeconds()).isEqualTo(1800L);
        }

        @Test
        @DisplayName("should return null for tokenExpiryBufferSeconds when not set")
        void shouldReturnNullWhenBufferNotSet() {
            // Given
            Auth0Properties.Management management = new Auth0Properties.Management(
                    "client-id",
                    "client-secret",
                    "https://example.auth0.com/api/v2/",
                    null
            );

            // Then
            assertThat(management.tokenExpiryBufferSeconds()).isNull();
            // But getter should return default
            assertThat(management.getTokenExpiryBufferSeconds()).isEqualTo(3600L);
        }
    }
}
