package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
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
 * Contract tests for POST /api/v1/account/plugins/bit-bi/rotate-api-key (#66).
 * <p>
 * Mirrors the Parquet Export rotation contract: the account owner rotates their own credential
 * over HTTP and the new secret is shown exactly once, in this response.
 * </p>
 */
@DisplayName("Bit BI API Key Rotation Contract Tests")
class BitBiApiKeyRotationContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String ROTATE_PATH = "/api/v1/account/plugins/bit-bi/rotate-api-key";

    @MockitoBean
    private PluginApiKeyService pluginApiKeyService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;

    @BeforeEach
    void setUp() {
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
        when(authorizationHelper.getOptionalAuthenticatedAccountId()).thenReturn(Optional.of(TEST_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Should return 200 with the new raw API key")
    void shouldReturnNewApiKey() throws Exception {
        PluginApiKey apiKey = PluginApiKey.generate();
        when(pluginApiKeyService.rotateApiKey(TEST_ACCOUNT_ID)).thenReturn(apiKey);

        mockMvc.perform(post(ROTATE_PATH)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(apiKey.value()));
    }

    @Test
    @DisplayName("Should return 403 when the plugin is not activated")
    void shouldReturn403WhenNotActivated() throws Exception {
        when(pluginApiKeyService.rotateApiKey(TEST_ACCOUNT_ID))
                .thenThrow(new PluginNotActivatedException("bit-bi", TEST_ACCOUNT_ID));

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
