package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginActivationService.ActivationResult;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginDataValidator;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotEnabledException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PluginActivationService.
 *
 * <p>Tests activation/deactivation workflow per FR-005, FR-006:
 * <ul>
 *   <li>Activate calls plugin.onActivate()</li>
 *   <li>Deactivate calls plugin.onDeactivate()</li>
 *   <li>Plugin not found throws PluginNotFoundException</li>
 *   <li>Plugin disabled throws PluginNotEnabledException</li>
 *   <li>Already deactivated throws PluginNotActivatedException</li>
 * </ul>
 *
 * @see PluginActivationService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginActivationService")
class PluginActivationServiceTest {

    @Mock
    private PluginRegistry pluginRegistry;

    @Mock
    private PluginConfigRepository pluginConfigRepository;

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private PluginDataValidator pluginDataValidator;

    @Mock
    private PluginAuditService pluginAuditService;

    @Mock
    private Plugin mockPlugin;

    @Mock
    private PluginConfig mockPluginConfig;

    @Captor
    private ArgumentCaptor<AccountPlugin> accountPluginCaptor;

    private PluginActivationService service;

    private UUID accountId;
    private String pluginId;
    private Map<String, Object> pluginData;

    @BeforeEach
    void setUp() {
        service = new PluginActivationService(
            pluginRegistry,
            pluginConfigRepository,
            accountPluginRepository,
            pluginDataValidator,
            pluginAuditService
        );

        accountId = UUID.randomUUID();
        pluginId = "bit-bi";
        pluginData = Map.of("tenantId", "tenant-123");
    }

    @Nested
    @DisplayName("activate()")
    class ActivateMethod {

        @Test
        @DisplayName("should create new activation and call onActivate hook")
        void shouldCreateNewActivationAndCallOnActivateHook() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.isEnabled()).thenReturn(true);
            when(mockPluginConfig.getDisplayName()).thenReturn("Bit BI");
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.empty());
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            ActivationResult result = service.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(result.isNewActivation()).isTrue();
            assertThat(result.pluginDisplayName()).isEqualTo("Bit BI");
            assertThat(result.accountPlugin().getPluginId()).isEqualTo(pluginId);
            assertThat(result.accountPlugin().getAccountId()).isEqualTo(accountId);
            assertThat(result.accountPlugin().isActive()).isTrue();

            verify(pluginDataValidator).validate(pluginId, pluginData);
            verify(mockPlugin).onActivate(any(AccountPlugin.class));
            verify(accountPluginRepository).save(any(AccountPlugin.class));
        }

        @Test
        @DisplayName("should update existing active activation")
        void shouldUpdateExistingActiveActivation() {
            // Given
            AccountPlugin existingPlugin = AccountPlugin.activate(accountId, pluginId, Map.of("tenantId", "old-tenant"));

            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.isEnabled()).thenReturn(true);
            when(mockPluginConfig.getDisplayName()).thenReturn("Bit BI");
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.of(existingPlugin));
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            ActivationResult result = service.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(result.isNewActivation()).isFalse();
            verify(mockPlugin).onActivate(any(AccountPlugin.class));
        }

        @Test
        @DisplayName("should reactivate deactivated plugin")
        void shouldReactivateDeactivatedPlugin() {
            // Given
            AccountPlugin existingPlugin = AccountPlugin.activate(accountId, pluginId, Map.of("tenantId", "old-tenant"));
            existingPlugin.deactivate();
            assertThat(existingPlugin.isActive()).isFalse();

            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.isEnabled()).thenReturn(true);
            when(mockPluginConfig.getDisplayName()).thenReturn("Bit BI");
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.of(existingPlugin));
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            ActivationResult result = service.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(result.isNewActivation()).isFalse();
            assertThat(result.accountPlugin().isActive()).isTrue();
            verify(mockPlugin).onActivate(any(AccountPlugin.class));
        }

        @Test
        @DisplayName("should throw PluginNotFoundException when plugin not in registry")
        void shouldThrowPluginNotFoundExceptionWhenPluginNotInRegistry() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.activate(accountId, pluginId, pluginData))
                .isInstanceOf(PluginNotFoundException.class)
                .hasMessageContaining(pluginId);

            verify(accountPluginRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw PluginNotFoundException when plugin not in database")
        void shouldThrowPluginNotFoundExceptionWhenPluginNotInDatabase() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.activate(accountId, pluginId, pluginData))
                .isInstanceOf(PluginNotFoundException.class);

            verify(accountPluginRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw PluginNotEnabledException when plugin is disabled")
        void shouldThrowPluginNotEnabledExceptionWhenPluginDisabled() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.isEnabled()).thenReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.activate(accountId, pluginId, pluginData))
                .isInstanceOf(PluginNotEnabledException.class)
                .hasMessageContaining(pluginId);

            verify(accountPluginRepository, never()).save(any());
        }

        @Test
        @DisplayName("should not fail activation when onActivate hook throws exception")
        void shouldNotFailActivationWhenOnActivateHookThrows() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.isEnabled()).thenReturn(true);
            when(mockPluginConfig.getDisplayName()).thenReturn("Bit BI");
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.empty());
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("Hook failed")).when(mockPlugin).onActivate(any());

            // When
            ActivationResult result = service.activate(accountId, pluginId, pluginData);

            // Then - activation should succeed despite hook failure
            assertThat(result.isNewActivation()).isTrue();
            verify(accountPluginRepository).save(any(AccountPlugin.class));
        }
    }

    @Nested
    @DisplayName("deactivate()")
    class DeactivateMethod {

        @Test
        @DisplayName("should deactivate active plugin and call onDeactivate hook")
        void shouldDeactivateActivePluginAndCallOnDeactivateHook() {
            // Given
            AccountPlugin activePlugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.of(activePlugin));
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            service.deactivate(accountId, pluginId);

            // Then
            assertThat(activePlugin.isActive()).isFalse();
            assertThat(activePlugin.getDeactivatedAt()).isNotNull();
            verify(mockPlugin).onDeactivate(activePlugin);
            verify(accountPluginRepository).save(activePlugin);
        }

        @Test
        @DisplayName("should throw PluginNotFoundException when plugin not in registry")
        void shouldThrowPluginNotFoundExceptionWhenPluginNotInRegistry() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deactivate(accountId, pluginId))
                .isInstanceOf(PluginNotFoundException.class);
        }

        @Test
        @DisplayName("should throw PluginNotActivatedException when no activation exists")
        void shouldThrowPluginNotActivatedExceptionWhenNoActivationExists() {
            // Given
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deactivate(accountId, pluginId))
                .isInstanceOf(PluginNotActivatedException.class)
                .hasMessageContaining(pluginId)
                .satisfies(e -> {
                    PluginNotActivatedException ex = (PluginNotActivatedException) e;
                    assertThat(ex.getPluginId()).isEqualTo(pluginId);
                    assertThat(ex.getAccountId()).isEqualTo(accountId);
                });
        }

        @Test
        @DisplayName("should throw PluginNotActivatedException when plugin already deactivated")
        void shouldThrowPluginNotActivatedExceptionWhenAlreadyDeactivated() {
            // Given
            AccountPlugin deactivatedPlugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            deactivatedPlugin.deactivate();

            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.of(deactivatedPlugin));

            // When / Then
            assertThatThrownBy(() -> service.deactivate(accountId, pluginId))
                .isInstanceOf(PluginNotActivatedException.class)
                .hasMessageContaining("already deactivated");
        }

        @Test
        @DisplayName("should not fail deactivation when onDeactivate hook throws exception")
        void shouldNotFailDeactivationWhenOnDeactivateHookThrows() {
            // Given
            AccountPlugin activePlugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(accountId, pluginId))
                .thenReturn(Optional.of(activePlugin));
            when(accountPluginRepository.save(any(AccountPlugin.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("Hook failed")).when(mockPlugin).onDeactivate(any());

            // When
            service.deactivate(accountId, pluginId);

            // Then - deactivation should succeed despite hook failure
            assertThat(activePlugin.isActive()).isFalse();
            verify(accountPluginRepository).save(activePlugin);
        }
    }

    @Nested
    @DisplayName("getPluginDisplayName()")
    class GetPluginDisplayNameMethod {

        @Test
        @DisplayName("should return display name from config")
        void shouldReturnDisplayNameFromConfig() {
            // Given
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.of(mockPluginConfig));
            when(mockPluginConfig.getDisplayName()).thenReturn("Bit BI Display Name");

            // When
            String result = service.getPluginDisplayName(pluginId);

            // Then
            assertThat(result).isEqualTo("Bit BI Display Name");
        }

        @Test
        @DisplayName("should return plugin name from registry when config not found")
        void shouldReturnPluginNameFromRegistryWhenConfigNotFound() {
            // Given
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.empty());
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(mockPlugin));
            when(mockPlugin.getName()).thenReturn("Bit BI Plugin");

            // When
            String result = service.getPluginDisplayName(pluginId);

            // Then
            assertThat(result).isEqualTo("Bit BI Plugin");
        }

        @Test
        @DisplayName("should return plugin ID when neither config nor registry has name")
        void shouldReturnPluginIdWhenNeitherConfigNorRegistryHasName() {
            // Given
            when(pluginConfigRepository.findByPluginId(pluginId)).thenReturn(Optional.empty());
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.empty());

            // When
            String result = service.getPluginDisplayName(pluginId);

            // Then
            assertThat(result).isEqualTo(pluginId);
        }
    }
}
