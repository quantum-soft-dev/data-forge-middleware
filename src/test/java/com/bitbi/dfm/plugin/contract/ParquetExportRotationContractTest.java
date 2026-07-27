package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for POST /api/v1/account/plugins/parquet-export/rotate-password
 * (028-parquet-export-plugin, T006).
 */
@DisplayName("Parquet Export Rotation Contract Tests")
class ParquetExportRotationContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String ROTATE_PATH = "/api/v1/account/plugins/parquet-export/rotate-password";

    @MockitoBean
    private ParquetExportCredentialsService credentialsService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;

    @BeforeEach
    void setUp() {
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
        when(authorizationHelper.getOptionalAuthenticatedAccountId()).thenReturn(Optional.of(TEST_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Should return 200 with login and new raw password")
    void shouldReturnNewCredentials() throws Exception {
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();
        when(credentialsService.rotatePassword(TEST_ACCOUNT_ID)).thenReturn(credentials);

        mockMvc.perform(post(ROTATE_PATH)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(credentials.login()))
                .andExpect(jsonPath("$.password").value(credentials.password()));
    }

    @Test
    @DisplayName("Should return 403 when plugin is not activated")
    void shouldReturn403WhenNotActivated() throws Exception {
        when(credentialsService.rotatePassword(TEST_ACCOUNT_ID))
                .thenThrow(new PluginNotActivatedException("parquet-export", TEST_ACCOUNT_ID));

        mockMvc.perform(post(ROTATE_PATH)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 401 without authentication")
    void shouldReturn401WithoutAuthentication() throws Exception {
        mockMvc.perform(post(ROTATE_PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
