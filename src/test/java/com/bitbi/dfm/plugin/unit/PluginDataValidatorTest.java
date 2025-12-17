package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.BitBiPlugin;
import com.bitbi.dfm.plugin.application.PluginDataValidator;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import com.bitbi.dfm.plugin.domain.exception.PluginDataValidationException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PluginDataValidator.
 *
 * <p>Tests JSON Schema validation functionality per FR-004:
 * <ul>
 *   <li>Valid tenantId passes validation</li>
 *   <li>Missing tenantId fails validation</li>
 *   <li>Invalid tenantId format fails validation</li>
 *   <li>Schema caching works correctly</li>
 * </ul>
 *
 * @see PluginDataValidator
 * @see BitBiPlugin
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginDataValidator")
class PluginDataValidatorTest {

    @Mock
    private PluginRegistry pluginRegistry;

    private PluginDataValidator validator;
    private ObjectMapper objectMapper;
    private Plugin bitBiPlugin;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        bitBiPlugin = new BitBiPlugin();
        validator = new PluginDataValidator(pluginRegistry, objectMapper);
    }

    @Nested
    @DisplayName("validate()")
    class ValidateMethod {

        @Test
        @DisplayName("should pass validation with valid tenantId")
        void shouldPassValidationWithValidTenantId() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then - no exception
            validator.validate(pluginId, pluginData);

            verify(pluginRegistry).findById(pluginId);
        }

        @Test
        @DisplayName("should pass validation with alphanumeric tenantId")
        void shouldPassValidationWithAlphanumericTenantId() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "ABC123def456");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then - no exception
            validator.validate(pluginId, pluginData);
        }

        @Test
        @DisplayName("should pass validation with underscores and hyphens in tenantId")
        void shouldPassValidationWithUnderscoresAndHyphens() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant_123-abc");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then - no exception
            validator.validate(pluginId, pluginData);
        }

        @Test
        @DisplayName("should fail validation when tenantId is missing")
        void shouldFailValidationWhenTenantIdMissing() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = new HashMap<>();
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class)
                .hasMessageContaining("bit-bi");
        }

        @Test
        @DisplayName("should fail validation when tenantId is null")
        void shouldFailValidationWhenTenantIdNull() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = new HashMap<>();
            pluginData.put("tenantId", null);
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class);
        }

        @Test
        @DisplayName("should fail validation when tenantId is empty string")
        void shouldFailValidationWhenTenantIdEmpty() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class)
                .hasMessageContaining("bit-bi");
        }

        @Test
        @DisplayName("should fail validation when tenantId contains invalid characters")
        void shouldFailValidationWhenTenantIdContainsInvalidChars() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant@123!");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class)
                .hasMessageContaining("bit-bi");
        }

        @Test
        @DisplayName("should fail validation when tenantId exceeds max length")
        void shouldFailValidationWhenTenantIdTooLong() {
            // Given
            String pluginId = "bit-bi";
            String longTenantId = "a".repeat(65); // Max is 64
            Map<String, Object> pluginData = Map.of("tenantId", longTenantId);
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class);
        }

        @Test
        @DisplayName("should fail validation when additional properties provided")
        void shouldFailValidationWithAdditionalProperties() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of(
                "tenantId", "valid-tenant",
                "extraField", "not allowed"
            );
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class);
        }

        @Test
        @DisplayName("should throw PluginNotFoundException when plugin not registered")
        void shouldThrowPluginNotFoundExceptionWhenPluginNotRegistered() {
            // Given
            String pluginId = "unknown-plugin";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginNotFoundException.class)
                .hasMessageContaining("unknown-plugin");
        }

        @Test
        @DisplayName("should return validation errors in exception")
        void shouldReturnValidationErrorsInException() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = new HashMap<>(); // Missing tenantId
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When / Then
            assertThatThrownBy(() -> validator.validate(pluginId, pluginData))
                .isInstanceOf(PluginDataValidationException.class)
                .satisfies(e -> {
                    PluginDataValidationException ex = (PluginDataValidationException) e;
                    assertThat(ex.getPluginId()).isEqualTo("bit-bi");
                    assertThat(ex.getValidationErrors()).isNotEmpty();
                });
        }
    }

    @Nested
    @DisplayName("Schema Caching")
    class SchemaCaching {

        @Test
        @DisplayName("should cache schema after first validation")
        void shouldCacheSchemaAfterFirstValidation() {
            // Given
            String pluginId = "bit-bi";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            when(pluginRegistry.findById(pluginId)).thenReturn(Optional.of(bitBiPlugin));

            // When - validate twice
            validator.validate(pluginId, pluginData);
            validator.validate(pluginId, pluginData);

            // Then - schema should be cached
            assertThat(validator.getCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return correct cache size")
        void shouldReturnCorrectCacheSize() {
            // Given - fresh validator
            assertThat(validator.getCacheSize()).isEqualTo(0);

            // When - validate
            when(pluginRegistry.findById("bit-bi")).thenReturn(Optional.of(bitBiPlugin));
            validator.validate("bit-bi", Map.of("tenantId", "tenant-123"));

            // Then
            assertThat(validator.getCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should clear cache when clearCache() called")
        void shouldClearCacheWhenClearCacheCalled() {
            // Given - validate to populate cache
            when(pluginRegistry.findById("bit-bi")).thenReturn(Optional.of(bitBiPlugin));
            validator.validate("bit-bi", Map.of("tenantId", "tenant-123"));
            assertThat(validator.getCacheSize()).isEqualTo(1);

            // When
            validator.clearCache();

            // Then
            assertThat(validator.getCacheSize()).isEqualTo(0);
        }
    }
}
