package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PluginApiKeyService} key lifecycle (031).
 * <p>
 * Every issued key must carry an indexed lookup handle so that validation stays O(1) in the
 * number of activations.
 * </p>
 */
@DisplayName("PluginApiKeyService — key lifecycle")
class PluginApiKeyServiceTest {

    private static final String PLUGIN_ID = "bit-bi";

    private AccountPluginRepository repository;
    private PasswordEncoder encoder;
    private PluginApiKeyService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        repository = mock(AccountPluginRepository.class);
        encoder = new BCryptPasswordEncoder();
        service = new PluginApiKeyService(repository, new SimpleMeterRegistry(), encoder);
        accountId = UUID.randomUUID();
        when(repository.save(any(AccountPlugin.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AccountPlugin activation(Map<String, Object> pluginData) {
        return AccountPlugin.activate(accountId, PLUGIN_ID, pluginData);
    }

    @Nested
    @DisplayName("generateApiKey()")
    class GenerateApiKey {

        @Test
        @DisplayName("should store the BCrypt hash and the indexed lookup")
        void shouldStoreHashAndLookup() {
            AccountPlugin activation = activation(Map.of("tenantId", "t-1"));
            when(repository.findById(7L)).thenReturn(Optional.of(activation));

            PluginApiKey key = service.generateApiKey(7L);

            assertThat(encoder.matches(key.value(), (String) activation.getPluginData().get("apiKeyHash")))
                    .as("BCrypt hash remains the verification source of truth")
                    .isTrue();
            assertThat(activation.getApiKeyLookup()).isEqualTo(PluginApiKey.lookupOf(key.value()));
        }

        @Test
        @DisplayName("should never persist the raw key")
        void shouldNotPersistRawKey() {
            AccountPlugin activation = activation(Map.of());
            when(repository.findById(7L)).thenReturn(Optional.of(activation));

            PluginApiKey key = service.generateApiKey(7L);

            assertThat(activation.getPluginData().values()).doesNotContain(key.value());
            assertThat(activation.getApiKeyLookup()).isNotEqualTo(key.value());
        }

        @Test
        @DisplayName("should drop a legacy plaintext apiKey field")
        void shouldDropLegacyPlaintextField() {
            Map<String, Object> legacy = new HashMap<>();
            legacy.put("apiKey", "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6");
            AccountPlugin activation = activation(legacy);
            when(repository.findById(7L)).thenReturn(Optional.of(activation));

            service.generateApiKey(7L);

            assertThat(activation.getPluginData()).doesNotContainKey("apiKey");
        }

        @Test
        @DisplayName("should fail for an unknown activation")
        void shouldFailForUnknownActivation() {
            when(repository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateApiKey(7L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("rotateApiKey()")
    class RotateApiKey {

        @Test
        @DisplayName("should replace both the hash and the lookup")
        void shouldReplaceHashAndLookup() {
            AccountPlugin activation = activation(Map.of());
            when(repository.findById(7L)).thenReturn(Optional.of(activation));
            when(repository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                    .thenReturn(Optional.of(activation));

            PluginApiKey first = service.generateApiKey(7L);
            String firstLookup = activation.getApiKeyLookup();
            String firstHash = (String) activation.getPluginData().get("apiKeyHash");

            PluginApiKey rotated = service.rotateApiKey(accountId);

            assertThat(rotated.value()).isNotEqualTo(first.value());
            assertThat(activation.getApiKeyLookup())
                    .isEqualTo(PluginApiKey.lookupOf(rotated.value()))
                    .isNotEqualTo(firstLookup);
            assertThat(activation.getPluginData().get("apiKeyHash")).isNotEqualTo(firstHash);
            assertThat(encoder.matches(first.value(), (String) activation.getPluginData().get("apiKeyHash")))
                    .as("the previous key must stop authenticating immediately")
                    .isFalse();
        }

        @Test
        @DisplayName("should fail when the account has no bit-bi activation")
        void shouldFailWithoutActivation() {
            when(repository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotateApiKey(accountId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
