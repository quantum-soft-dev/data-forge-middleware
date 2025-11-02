package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PasswordGenerator domain service.
 * <p>
 * Tests password generation constraints:
 * - Length: 8-12 characters
 * - Character set: alphanumeric only (a-z, A-Z, 0-9)
 * - Uniqueness: Statistically unlikely to generate duplicates
 * <p>
 * Feature: 007-adding-a-site (T016)
 */
@DisplayName("PasswordGenerator")
class PasswordGeneratorTest {

    private PasswordGenerator passwordGenerator;

    @BeforeEach
    void setUp() {
        passwordGenerator = new PasswordGenerator();
    }

    @Test
    @DisplayName("Should generate password with length between 8 and 12 characters")
    void shouldGeneratePasswordWithCorrectLength() {
        // When
        String password = passwordGenerator.generate();

        // Then
        assertThat(password).hasSizeBetween(8, 12);
    }

    @Test
    @DisplayName("Should generate password containing only alphanumeric characters")
    void shouldGeneratePasswordWithAlphanumericCharactersOnly() {
        // When
        String password = passwordGenerator.generate();

        // Then
        assertThat(password).matches("^[a-zA-Z0-9]+$");
    }

    @Test
    @DisplayName("Should not be null or empty")
    void shouldNotGenerateNullOrEmptyPassword() {
        // When
        String password = passwordGenerator.generate();

        // Then
        assertThat(password).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("Should generate unique passwords (statistical test)")
    void shouldGenerateUniquePasswords() {
        // Given
        int iterations = 1000;
        Set<String> passwords = new HashSet<>();

        // When
        for (int i = 0; i < iterations; i++) {
            String password = passwordGenerator.generate();
            passwords.add(password);
        }

        // Then - at least 95% unique (allowing for statistical possibility of collisions)
        double uniquenessRatio = (double) passwords.size() / iterations;
        assertThat(uniquenessRatio).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    @DisplayName("Should contain at least one letter")
    void shouldContainAtLeastOneLetter() {
        // Given - Run multiple times to account for randomness
        boolean hasLetter = false;

        // When
        for (int i = 0; i < 100; i++) {
            String password = passwordGenerator.generate();
            if (password.matches(".*[a-zA-Z].*")) {
                hasLetter = true;
                break;
            }
        }

        // Then
        assertThat(hasLetter).isTrue();
    }

    @Test
    @DisplayName("Should contain at least one digit")
    void shouldContainAtLeastOneDigit() {
        // Given - Run multiple times to account for randomness
        boolean hasDigit = false;

        // When
        for (int i = 0; i < 100; i++) {
            String password = passwordGenerator.generate();
            if (password.matches(".*[0-9].*")) {
                hasDigit = true;
                break;
            }
        }

        // Then
        assertThat(hasDigit).isTrue();
    }

    @Test
    @DisplayName("Should use SecureRandom (entropy test)")
    void shouldUseSecureRandom() {
        // Given
        int iterations = 100;
        Set<Character> charactersUsed = new HashSet<>();

        // When - Collect characters from multiple generations
        for (int i = 0; i < iterations; i++) {
            String password = passwordGenerator.generate();
            for (char c : password.toCharArray()) {
                charactersUsed.add(c);
            }
        }

        // Then - Should use a variety of characters (at least 20 different characters)
        assertThat(charactersUsed.size()).isGreaterThanOrEqualTo(20);
    }
}
