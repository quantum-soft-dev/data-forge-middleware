package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.domain.PluginApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PluginApiKey value object.
 * Tests key format validation, generation, and security features.
 *
 * TDD: These tests are written FIRST and should FAIL until implementation.
 */
@DisplayName("PluginApiKey")
class PluginApiKeyTest {

    @Nested
    @DisplayName("Key Format Validation")
    class KeyFormatValidation {

        @Test
        @DisplayName("should accept valid key format (plk_ + 32 alphanumeric)")
        void shouldAcceptValidKeyFormat() {
            // Given
            String validKey = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";

            // When
            PluginApiKey apiKey = PluginApiKey.of(validKey);

            // Then
            assertThat(apiKey.value()).isEqualTo(validKey);
        }

        @Test
        @DisplayName("should reject key without plk_ prefix")
        void shouldRejectKeyWithoutPrefix() {
            // Given
            String invalidKey = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6xx";

            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid API key format");
        }

        @Test
        @DisplayName("should reject key with wrong prefix")
        void shouldRejectKeyWithWrongPrefix() {
            // Given
            String invalidKey = "api_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";

            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(invalidKey))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject key that is too short")
        void shouldRejectKeyThatIsTooShort() {
            // Given
            String shortKey = "plk_abc123";

            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(shortKey))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject key that is too long")
        void shouldRejectKeyThatIsTooLong() {
            // Given
            String longKey = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6EXTRA";

            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(longKey))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject key with special characters")
        void shouldRejectKeyWithSpecialCharacters() {
            // Given
            String keyWithSpecial = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5!@";

            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(keyWithSpecial))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject null key value")
        void shouldRejectNullKeyValue() {
            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject empty key value")
        void shouldRejectEmptyKeyValue() {
            // When / Then
            assertThatThrownBy(() -> PluginApiKey.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Key Generation")
    class KeyGeneration {

        @Test
        @DisplayName("should generate key with plk_ prefix")
        void shouldGenerateKeyWithPrefix() {
            // When
            PluginApiKey apiKey = PluginApiKey.generate();

            // Then
            assertThat(apiKey.value()).startsWith("plk_");
        }

        @Test
        @DisplayName("should generate key with exactly 36 characters")
        void shouldGenerateKeyWithExactLength() {
            // When
            PluginApiKey apiKey = PluginApiKey.generate();

            // Then
            assertThat(apiKey.value()).hasSize(36); // "plk_" (4) + 32 = 36
        }

        @Test
        @DisplayName("should generate valid key format")
        void shouldGenerateValidKeyFormat() {
            // When
            PluginApiKey apiKey = PluginApiKey.generate();

            // Then
            assertThat(apiKey.value()).matches("^plk_[a-zA-Z0-9]{32}$");
        }

        @Test
        @DisplayName("should generate unique keys on each call")
        void shouldGenerateUniqueKeysOnEachCall() {
            // Given
            Set<String> keys = new HashSet<>();

            // When
            for (int i = 0; i < 100; i++) {
                keys.add(PluginApiKey.generate().value());
            }

            // Then - all keys should be unique
            assertThat(keys).hasSize(100);
        }

        @Test
        @DisplayName("should generate key with alphanumeric characters only")
        void shouldGenerateKeyWithAlphanumericOnly() {
            // When
            PluginApiKey apiKey = PluginApiKey.generate();
            String keyPart = apiKey.value().substring(4); // Remove "plk_" prefix

            // Then
            assertThat(keyPart).matches("[a-zA-Z0-9]+");
        }
    }

    @Nested
    @DisplayName("isValid() Static Method")
    class IsValidMethod {

        @Test
        @DisplayName("should return true for valid key")
        void shouldReturnTrueForValidKey() {
            // Given
            String validKey = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";

            // When / Then
            assertThat(PluginApiKey.isValid(validKey)).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid key")
        void shouldReturnFalseForInvalidKey() {
            // Given
            String invalidKey = "invalid_key";

            // When / Then
            assertThat(PluginApiKey.isValid(invalidKey)).isFalse();
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            // When / Then
            assertThat(PluginApiKey.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("should return false for empty string")
        void shouldReturnFalseForEmptyString() {
            // When / Then
            assertThat(PluginApiKey.isValid("")).isFalse();
        }
    }

    @Nested
    @DisplayName("Security Features")
    class SecurityFeatures {

        @Test
        @DisplayName("toString() should mask the key value")
        void toStringShouldMaskTheKeyValue() {
            // Given
            PluginApiKey apiKey = PluginApiKey.of("plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6");

            // When
            String stringRepresentation = apiKey.toString();

            // Then - should show prefix + **** + last 4 chars
            assertThat(stringRepresentation).isEqualTo("plk_****o5P6");
            assertThat(stringRepresentation).doesNotContain("a1B2c3D4e5F6g7H8i9J0k1L2m3N4");
        }

        @Test
        @DisplayName("rawValue() should return full key value")
        void rawValueShouldReturnFullKeyValue() {
            // Given
            String fullKey = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";
            PluginApiKey apiKey = PluginApiKey.of(fullKey);

            // When
            String rawValue = apiKey.rawValue();

            // Then
            assertThat(rawValue).isEqualTo(fullKey);
        }
    }

    @Nested
    @DisplayName("Record Equality")
    class RecordEquality {

        @Test
        @DisplayName("should be equal for same key value")
        void shouldBeEqualForSameKeyValue() {
            // Given
            String key = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";

            // When
            PluginApiKey key1 = PluginApiKey.of(key);
            PluginApiKey key2 = PluginApiKey.of(key);

            // Then
            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different key values")
        void shouldNotBeEqualForDifferentKeyValues() {
            // Given
            PluginApiKey key1 = PluginApiKey.generate();
            PluginApiKey key2 = PluginApiKey.generate();

            // Then
            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
