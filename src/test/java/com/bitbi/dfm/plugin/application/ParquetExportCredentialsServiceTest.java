package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ParquetExportCredentialsService} (028-parquet-export-plugin, T004).
 */
@ExtendWith(MockitoExtension.class)
class ParquetExportCredentialsServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ParquetExportCredentialsService service;

    @BeforeEach
    void setUp() {
        service = new ParquetExportCredentialsService(accountPluginRepository, eventPublisher);
        lenient().when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AccountPlugin activation(Map<String, Object> pluginData) {
        return AccountPlugin.activate(ACCOUNT_ID, "parquet-export", pluginData);
    }

    @Nested
    @DisplayName("mintCredentials")
    class MintCredentials {

        @Test
        @DisplayName("Should mint and store login + BCrypt hash on first activation")
        void shouldMintCredentialsOnFirstActivation() {
            AccountPlugin accountPlugin = activation(Map.of());

            Optional<ParquetExportCredentials> minted = service.mintCredentials(accountPlugin);

            assertTrue(minted.isPresent());
            Map<String, Object> data = accountPlugin.getPluginData();
            assertEquals(minted.get().login(), data.get("login"));
            String hash = (String) data.get("passwordHash");
            assertNotNull(hash);
            assertTrue(ENCODER.matches(minted.get().password(), hash));
            assertFalse(data.containsValue(minted.get().password()), "raw password must never be stored");
            verify(accountPluginRepository).save(accountPlugin);
        }

        @Test
        @DisplayName("Should not mint when credentials already exist")
        void shouldNotMintWhenCredentialsExist() {
            AccountPlugin accountPlugin = activation(Map.of("login", "pex_existing0001", "passwordHash", "$2a$x"));

            Optional<ParquetExportCredentials> minted = service.mintCredentials(accountPlugin);

            assertTrue(minted.isEmpty());
            assertEquals("pex_existing0001", accountPlugin.getPluginData().get("login"));
            verify(accountPluginRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("rotatePassword")
    class RotatePassword {

        @Test
        @DisplayName("Should keep login and replace hash on rotation")
        void shouldRotatePassword() {
            AccountPlugin accountPlugin = activation(Map.of("login", "pex_stableLogin1",
                    "passwordHash", ENCODER.encode("oldPassword")));
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "parquet-export"))
                    .thenReturn(Optional.of(accountPlugin));

            ParquetExportCredentials rotated = service.rotatePassword(ACCOUNT_ID);

            assertEquals("pex_stableLogin1", rotated.login());
            String newHash = (String) accountPlugin.getPluginData().get("passwordHash");
            assertTrue(ENCODER.matches(rotated.password(), newHash));
            assertFalse(ENCODER.matches("oldPassword", newHash));
            verify(accountPluginRepository).save(accountPlugin);
            verify(eventPublisher).publishEvent(new com.bitbi.dfm.plugin.domain.PluginCredentialRotatedEvent(
                    "parquet-export", ACCOUNT_ID, com.bitbi.dfm.plugin.domain.PluginActionType.PASSWORD_ROTATED));
        }

        @Test
        @DisplayName("Should throw when plugin is not activated")
        void shouldThrowWhenNotActivated() {
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "parquet-export"))
                    .thenReturn(Optional.empty());

            assertThrows(PluginNotActivatedException.class, () -> service.rotatePassword(ACCOUNT_ID));
        }

        @Test
        @DisplayName("Should throw when activation is inactive")
        void shouldThrowWhenInactive() {
            AccountPlugin accountPlugin = activation(Map.of("login", "pex_stableLogin1", "passwordHash", "$2a$x"));
            accountPlugin.deactivate();
            when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "parquet-export"))
                    .thenReturn(Optional.of(accountPlugin));

            assertThrows(PluginNotActivatedException.class, () -> service.rotatePassword(ACCOUNT_ID));
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        private AccountPlugin activeWith(String login, String rawPassword) {
            return activation(Map.of("login", login, "passwordHash", ENCODER.encode(rawPassword)));
        }

        @Test
        @DisplayName("Should return activation via direct login lookup")
        void shouldValidateCorrectCredentials() {
            AccountPlugin accountPlugin = activeWith("pex_someLogin001", "correctPassword");
            when(accountPluginRepository.findActiveByPluginIdAndLogin("parquet-export", "pex_someLogin001"))
                    .thenReturn(Optional.of(accountPlugin));

            Optional<AccountPlugin> result = service.validate("pex_someLogin001", "correctPassword");

            assertTrue(result.isPresent());
            assertSame(accountPlugin, result.get());
            // review P1: one point query, never a scan of all activations
            verify(accountPluginRepository, never()).findActiveByPluginId(any());
        }

        @Test
        @DisplayName("Should reject wrong password")
        void shouldRejectWrongPassword() {
            AccountPlugin accountPlugin = activeWith("pex_someLogin001", "correctPassword");
            when(accountPluginRepository.findActiveByPluginIdAndLogin("parquet-export", "pex_someLogin001"))
                    .thenReturn(Optional.of(accountPlugin));

            assertTrue(service.validate("pex_someLogin001", "wrongPassword").isEmpty());
        }

        @Test
        @DisplayName("Should reject unknown login without BCrypt check")
        void shouldRejectUnknownLogin() {
            when(accountPluginRepository.findActiveByPluginIdAndLogin("parquet-export", "pex_otherLogin99"))
                    .thenReturn(Optional.empty());

            assertTrue(service.validate("pex_otherLogin99", "correctPassword").isEmpty());
        }

        @Test
        @DisplayName("Should reject null inputs")
        void shouldRejectNullInputs() {
            assertTrue(service.validate(null, "x").isEmpty());
            assertTrue(service.validate("pex_someLogin001", null).isEmpty());
            verifyNoInteractions(accountPluginRepository);
        }

        @Test
        @DisplayName("Should reject activation without stored hash")
        void shouldRejectMissingHash() {
            when(accountPluginRepository.findActiveByPluginIdAndLogin("parquet-export", "pex_someLogin001"))
                    .thenReturn(Optional.of(activation(Map.of("login", "pex_someLogin001"))));

            assertTrue(service.validate("pex_someLogin001", "anyPassword").isEmpty());
        }
    }
}
