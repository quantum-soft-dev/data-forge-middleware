package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private CountingPasswordEncoder encoder;
    private SimpleMeterRegistry meterRegistry;
    private PluginAuditService auditService;
    private PluginApiKeyService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        repository = mock(AccountPluginRepository.class);
        encoder = new CountingPasswordEncoder();
        meterRegistry = new SimpleMeterRegistry();
        auditService = mock(PluginAuditService.class);
        service = new PluginApiKeyService(repository, meterRegistry, auditService, encoder);
        accountId = UUID.randomUUID();
        when(repository.save(any(AccountPlugin.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private double counter(String name) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0d : counter.count();
    }

    private AccountPlugin activation(Map<String, Object> pluginData) {
        return AccountPlugin.activate(accountId, PLUGIN_ID, pluginData);
    }

    /**
     * BCrypt encoder that records how often it is used — the whole point of 031 is that a
     * validation costs exactly one comparison and never an encode.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        private int matchesCalls;
        private int encodeCalls;

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls++;
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls++;
            return delegate.matches(rawPassword, encodedPassword);
        }

        void reset() {
            matchesCalls = 0;
            encodeCalls = 0;
        }
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
                    .isInstanceOf(PluginNotActivatedException.class);
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("should fail when the activation is deactivated")
        void shouldFailForDeactivatedActivation() {
            AccountPlugin activation = activation(Map.of());
            activation.deactivate();
            when(repository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                    .thenReturn(Optional.of(activation));

            assertThatThrownBy(() -> service.rotateApiKey(accountId))
                    .as("a deactivated plugin must not mint a working key")
                    .isInstanceOf(PluginNotActivatedException.class);
            verify(repository, never()).save(any(AccountPlugin.class));
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("should record the rotation in the audit log")
        void shouldAuditRotation() {
            AccountPlugin activation = activation(Map.of());
            when(repository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                    .thenReturn(Optional.of(activation));

            service.rotateApiKey(accountId);

            verify(auditService).logApiKeyRotated(PLUGIN_ID, accountId);
        }

        @Test
        @DisplayName("rotated key should validate, previous key should not")
        void rotatedKeyShouldValidate() {
            AccountPlugin activation = activation(Map.of());
            when(repository.findById(7L)).thenReturn(Optional.of(activation));
            when(repository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                    .thenReturn(Optional.of(activation));

            PluginApiKey first = service.generateApiKey(7L);
            PluginApiKey rotated = service.rotateApiKey(accountId);

            when(repository.findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, rotated.lookup()))
                    .thenReturn(Optional.of(activation));
            when(repository.findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, first.lookup()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of());

            assertThat(service.validateApiKey(rotated.value())).contains(activation);
            assertThat(service.validateApiKey(first.value())).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateApiKey()")
    class ValidateApiKey {

        private final String rawKey = PluginApiKey.generate().value();
        private final String lookup = PluginApiKey.lookupOf(rawKey);

        private AccountPlugin activationWithLookup(String key) {
            AccountPlugin activation = activation(Map.of("apiKeyHash", encoder.encode(key)));
            activation.updateApiKeyLookup(PluginApiKey.lookupOf(key));
            encoder.reset();
            return activation;
        }

        @Test
        @DisplayName("should accept a valid key with exactly one BCrypt comparison")
        void shouldAcceptValidKeyWithSingleBcryptComparison() {
            AccountPlugin activation = activationWithLookup(rawKey);
            when(repository.findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, lookup))
                    .thenReturn(Optional.of(activation));

            Optional<AccountPlugin> result = service.validateApiKey(rawKey);

            assertThat(result).contains(activation);
            assertThat(encoder.matchesCalls).as("exactly one BCrypt comparison").isEqualTo(1);
            assertThat(encoder.encodeCalls).as("no BCrypt work on the read path").isZero();
            verify(repository, never()).findActiveByPluginId(any());
            verify(repository, never()).findActiveByPluginIdWithoutApiKeyLookup(any());
        }

        @Test
        @DisplayName("should reject an unknown key without scanning every activation")
        void shouldRejectUnknownKeyWithoutScan() {
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of());

            Optional<AccountPlugin> result = service.validateApiKey(rawKey);

            assertThat(result).isEmpty();
            assertThat(encoder.matchesCalls).as("no BCrypt work for an unknown key").isZero();
            verify(repository, never()).findActiveByPluginId(any());
        }

        @Test
        @DisplayName("should reject a malformed key before touching the database")
        void shouldRejectMalformedKeyWithoutDbAccess() {
            assertThat(service.validateApiKey("not-a-plugin-key")).isEmpty();
            assertThat(service.validateApiKey("")).isEmpty();
            assertThat(service.validateApiKey(null)).isEmpty();

            verifyNoInteractions(repository);
            assertThat(encoder.matchesCalls).isZero();
        }

        @Test
        @DisplayName("should reject an inactive activation even if the lookup resolves")
        void shouldRejectInactiveActivation() {
            AccountPlugin activation = activationWithLookup(rawKey);
            activation.deactivate();
            when(repository.findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, lookup))
                    .thenReturn(Optional.of(activation));
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of());

            assertThat(service.validateApiKey(rawKey)).isEmpty();
        }

        @Test
        @DisplayName("should reject a key whose lookup resolves but whose hash does not match")
        void shouldRejectMismatchedHash() {
            AccountPlugin activation = activationWithLookup(PluginApiKey.generate().value());
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.of(activation));
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of());

            assertThat(service.validateApiKey(rawKey)).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateApiKey() — legacy activations without a lookup")
    class ValidateLegacyActivations {

        private final String rawKey = PluginApiKey.generate().value();

        @Test
        @DisplayName("should still authenticate a hashed key that predates the lookup column")
        void shouldAuthenticateLegacyHashedKey() {
            AccountPlugin legacy = activation(Map.of("apiKeyHash", encoder.encode(rawKey)));
            encoder.reset();
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).contains(legacy);
            assertThat(encoder.encodeCalls).as("no BCrypt encode on the read path").isZero();
        }

        @Test
        @DisplayName("should still authenticate a plaintext key without hashing it on read")
        void shouldAuthenticateLegacyPlaintextKey() {
            AccountPlugin legacy = activation(Map.of("apiKey", rawKey));
            encoder.reset();
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).contains(legacy);
            assertThat(encoder.encodeCalls)
                    .as("comparing against a plaintext key must not mint a BCrypt hash")
                    .isZero();
            assertThat(encoder.matchesCalls).isZero();
        }

        @Test
        @DisplayName("should reject a wrong key against a legacy plaintext activation")
        void shouldRejectWrongKeyAgainstLegacyPlaintext() {
            AccountPlugin legacy = activation(Map.of("apiKey", PluginApiKey.generate().value()));
            encoder.reset();
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).isEmpty();
            assertThat(encoder.encodeCalls).isZero();
        }

        @Test
        @DisplayName("should ignore legacy rows with no credentials at all")
        void shouldIgnoreRowsWithoutCredentials() {
            AccountPlugin legacy = activation(Map.of("tenantId", "t-1"));
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateApiKey() — legacy fallback metrics")
    class LegacyFallbackMetrics {

        private static final String SCAN = "plugin.api.key.validation.legacy.scan";
        private static final String HIT = "plugin.api.key.validation.legacy.hit";

        private final String rawKey = PluginApiKey.generate().value();

        @Test
        @DisplayName("indexed hit should not touch the legacy counters")
        void indexedHitShouldNotCount() {
            AccountPlugin activation = activation(Map.of("apiKeyHash", encoder.encode(rawKey)));
            activation.updateApiKeyLookup(PluginApiKey.lookupOf(rawKey));
            when(repository.findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, PluginApiKey.lookupOf(rawKey)))
                    .thenReturn(Optional.of(activation));

            assertThat(service.validateApiKey(rawKey)).isPresent();

            assertThat(counter(SCAN)).isZero();
            assertThat(counter(HIT)).isZero();
        }

        @Test
        @DisplayName("should not count a scan when no legacy activations remain")
        void shouldNotCountScanWhenNoLegacyRows() {
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of());

            assertThat(service.validateApiKey(rawKey)).isEmpty();

            assertThat(counter(SCAN)).isZero();
            assertThat(counter(HIT)).isZero();
        }

        @Test
        @DisplayName("should count a scan when legacy activations are searched")
        void shouldCountScan() {
            AccountPlugin legacy = activation(Map.of("apiKeyHash", encoder.encode("plk_" + "x".repeat(32))));
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).isEmpty();

            assertThat(counter(SCAN)).isEqualTo(1d);
            assertThat(counter(HIT)).as("no legacy activation authenticated").isZero();
        }

        @Test
        @DisplayName("should count a hit when a legacy activation authenticates")
        void shouldCountHit() {
            AccountPlugin legacy = activation(Map.of("apiKeyHash", encoder.encode(rawKey)));
            when(repository.findActiveByPluginIdAndApiKeyLookup(eq(PLUGIN_ID), any()))
                    .thenReturn(Optional.empty());
            when(repository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                    .thenReturn(List.of(legacy));

            assertThat(service.validateApiKey(rawKey)).contains(legacy);

            assertThat(counter(SCAN)).isEqualTo(1d);
            assertThat(counter(HIT)).isEqualTo(1d);
        }
    }
}
