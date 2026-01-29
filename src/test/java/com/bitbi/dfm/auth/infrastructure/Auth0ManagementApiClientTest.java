package com.bitbi.dfm.auth.infrastructure;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.APIException;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.auth0.net.Response;
import com.bitbi.dfm.auth.application.Auth0TokenProvider;
import com.bitbi.dfm.auth.config.Auth0Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Auth0ManagementApiClient.
 * <p>
 * Tests the token retry logic and user management operations.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("Auth0ManagementApiClient Unit Tests")
class Auth0ManagementApiClientTest {

    @Mock
    private Auth0Properties properties;

    @Mock
    private Auth0TokenProvider tokenProvider;

    @Mock
    private ManagementAPI.Builder managementAPIBuilder;

    @Mock
    private ManagementAPI managementAPI;

    @Mock
    private UsersEntity usersEntity;

    @Mock
    private Request<User> userRequest;

    private Auth0ManagementApiClient client;

    @BeforeEach
    void setUp() throws Auth0Exception {
        when(properties.domain()).thenReturn("test.auth0.com");
        when(tokenProvider.getAccessToken()).thenReturn("test-token");
        client = new Auth0ManagementApiClient(properties, tokenProvider);
    }

    @Test
    @DisplayName("Should create user with password successfully")
    void shouldCreateUserWithPasswordSuccessfully() throws Auth0Exception {
        // Given
        String email = "test@example.com";
        String name = "Test User";
        String password = "SecurePassword123!";
        boolean verifyEmail = true;

        User createdUser = new User();
        createdUser.setId("auth0|123456");
        createdUser.setEmail(email);

        try (MockedStatic<ManagementAPI> managementAPIMock = mockStatic(ManagementAPI.class)) {
            managementAPIMock.when(() -> ManagementAPI.newBuilder(anyString(), anyString()))
                    .thenReturn(managementAPIBuilder);
            when(managementAPIBuilder.build()).thenReturn(managementAPI);
            when(managementAPI.users()).thenReturn(usersEntity);
            when(usersEntity.create(any(User.class))).thenReturn(userRequest);
            when(userRequest.execute()).thenReturn(new MockResponse<>(createdUser));

            // When
            User result = client.createUserWithPassword(email, name, password, verifyEmail);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("auth0|123456");
            assertThat(result.getEmail()).isEqualTo(email);

            verify(usersEntity).create(any(User.class));
        }
    }

    @Test
    @DisplayName("Should retry with refreshed token on 401 error")
    void shouldRetryWithRefreshedTokenOn401Error() throws Auth0Exception {
        // Given
        String email = "test@example.com";
        String name = "Test User";
        String password = "SecurePassword123!";

        User createdUser = new User();
        createdUser.setId("auth0|123456");

        APIException tokenExpiredException = new APIException("Unauthorized", 401, null);

        try (MockedStatic<ManagementAPI> managementAPIMock = mockStatic(ManagementAPI.class)) {
            managementAPIMock.when(() -> ManagementAPI.newBuilder(anyString(), anyString()))
                    .thenReturn(managementAPIBuilder);
            when(managementAPIBuilder.build()).thenReturn(managementAPI);
            when(managementAPI.users()).thenReturn(usersEntity);
            when(usersEntity.create(any(User.class))).thenReturn(userRequest);

            // First call throws 401, second call succeeds
            when(userRequest.execute())
                    .thenThrow(tokenExpiredException)
                    .thenReturn(new MockResponse<>(createdUser));

            // When
            User result = client.createUserWithPassword(email, name, password, true);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("auth0|123456");

            // Verify token was refreshed
            verify(tokenProvider).refreshToken();
            // Verify token was fetched twice (original + after refresh)
            verify(tokenProvider, times(2)).getAccessToken();
        }
    }

    @Test
    @DisplayName("Should throw exception when user creation returns null")
    void shouldThrowExceptionWhenUserCreationReturnsNull() throws Auth0Exception {
        // Given
        String email = "test@example.com";
        String name = "Test User";
        String password = "SecurePassword123!";

        try (MockedStatic<ManagementAPI> managementAPIMock = mockStatic(ManagementAPI.class)) {
            managementAPIMock.when(() -> ManagementAPI.newBuilder(anyString(), anyString()))
                    .thenReturn(managementAPIBuilder);
            when(managementAPIBuilder.build()).thenReturn(managementAPI);
            when(managementAPI.users()).thenReturn(usersEntity);
            when(usersEntity.create(any(User.class))).thenReturn(userRequest);
            when(userRequest.execute()).thenReturn(new MockResponse<>(null));

            // When / Then
            assertThatThrownBy(() -> client.createUserWithPassword(email, name, password, true))
                    .isInstanceOf(Auth0Exception.class)
                    .hasMessageContaining("User creation returned null response");
        }
    }

    @Test
    @DisplayName("Should throw exception when user has null ID")
    void shouldThrowExceptionWhenUserHasNullId() throws Auth0Exception {
        // Given
        String email = "test@example.com";
        String name = "Test User";
        String password = "SecurePassword123!";

        User createdUser = new User();
        createdUser.setId(null); // Null ID
        createdUser.setEmail(email);

        try (MockedStatic<ManagementAPI> managementAPIMock = mockStatic(ManagementAPI.class)) {
            managementAPIMock.when(() -> ManagementAPI.newBuilder(anyString(), anyString()))
                    .thenReturn(managementAPIBuilder);
            when(managementAPIBuilder.build()).thenReturn(managementAPI);
            when(managementAPI.users()).thenReturn(usersEntity);
            when(usersEntity.create(any(User.class))).thenReturn(userRequest);
            when(userRequest.execute()).thenReturn(new MockResponse<>(createdUser));

            // When / Then
            assertThatThrownBy(() -> client.createUserWithPassword(email, name, password, true))
                    .isInstanceOf(Auth0Exception.class)
                    .hasMessageContaining("User creation returned null response");
        }
    }

    /**
     * Mock response wrapper for Auth0 Request objects.
     */
    private static class MockResponse<T> implements Response<T> {
        private final T body;

        public MockResponse(T body) {
            this.body = body;
        }

        @Override
        public T getBody() {
            return body;
        }

        @Override
        public int getStatusCode() {
            return 200;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }
    }
}
