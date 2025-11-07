package com.bitbi.dfm.auth;

import com.bitbi.dfm.auth.domain.Auth0UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for Auth0UserId value object.
 * <p>
 * Tests validation rules:
 * - Cannot be null or blank
 * - Cannot exceed 64 characters
 * - Must match format: {provider}|{alphanumeric}
 * </p>
 *
 * User Story: US1 - Admin Creates User Account via Auth0
 * Task: T030 - Unit test for Auth0UserId value object
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("Auth0UserId Value Object Tests")
class Auth0UserIdTest {

    /**
     * TC01: Valid Auth0 database connection user ID accepted
     * <p>
     * Given: Valid Auth0 user ID (auth0|abc123)
     * When: Create Auth0UserId
     * Then: Creation succeeds
     * And: Value matches input
     * And: Provider extracted correctly
     * And: Identifier extracted correctly
     * </p>
     */
    @Test
    @DisplayName("TC01: Valid Auth0 database connection user ID accepted")
    void auth0UserId_validDatabaseConnection_accepted() {
        // Given
        String validUserId = "auth0|507f1f77bcf86cd799439011";

        // When
        Auth0UserId userId = Auth0UserId.of(validUserId);

        // Then
        assertThat(userId).isNotNull();
        assertThat(userId.value()).isEqualTo(validUserId);
        assertThat(userId.provider()).isEqualTo("auth0");
        assertThat(userId.identifier()).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(userId.isDatabaseConnection()).isTrue();
        assertThat(userId.toString()).isEqualTo(validUserId);
    }

    /**
     * TC02: Valid Google OAuth2 user ID accepted
     * <p>
     * Given: Valid Google OAuth2 user ID (google-oauth2|123)
     * When: Create Auth0UserId
     * Then: Creation succeeds
     * And: Provider extracted correctly
     * And: isDatabaseConnection returns false
     * </p>
     */
    @Test
    @DisplayName("TC02: Valid Google OAuth2 user ID accepted")
    void auth0UserId_validGoogleOAuth2_accepted() {
        // Given
        String validUserId = "google-oauth2|115015365098652987163";

        // When
        Auth0UserId userId = Auth0UserId.of(validUserId);

        // Then
        assertThat(userId).isNotNull();
        assertThat(userId.provider()).isEqualTo("google-oauth2");
        assertThat(userId.identifier()).isEqualTo("115015365098652987163");
        assertThat(userId.isDatabaseConnection()).isFalse();
    }

    /**
     * TC03: Valid Microsoft Account user ID accepted
     * <p>
     * Given: Valid Windows Live user ID (windowslive|4d35b10d6f5b07e8)
     * When: Create Auth0UserId
     * Then: Creation succeeds
     * And: Provider extracted correctly
     * </p>
     */
    @Test
    @DisplayName("TC03: Valid Microsoft Account user ID accepted")
    void auth0UserId_validWindowsLive_accepted() {
        // Given
        String validUserId = "windowslive|4d35b10d6f5b07e8";

        // When
        Auth0UserId userId = Auth0UserId.of(validUserId);

        // Then
        assertThat(userId).isNotNull();
        assertThat(userId.provider()).isEqualTo("windowslive");
        assertThat(userId.identifier()).isEqualTo("4d35b10d6f5b07e8");
        assertThat(userId.isDatabaseConnection()).isFalse();
    }

    /**
     * TC04: Null user ID throws exception
     * <p>
     * Given: Null user ID
     * When: Create Auth0UserId
     * Then: NullPointerException thrown
     * </p>
     */
    @Test
    @DisplayName("TC04: Null user ID throws NullPointerException")
    void auth0UserId_nullValue_throwsException() {
        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Auth0 user ID cannot be null");
    }

    /**
     * TC05: Blank user ID throws exception
     * <p>
     * Given: Blank user ID (empty string, whitespace)
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("TC05: Blank user ID throws IllegalArgumentException")
    void auth0UserId_blankValue_throwsException(String blankValue) {
        // When/Then
        assertThatThrownBy(() -> {
            if (blankValue != null) {
                Auth0UserId.of(blankValue);
            } else {
                Auth0UserId.of(null);
            }
        }).isInstanceOf(RuntimeException.class); // NullPointerException or IllegalArgumentException
    }

    /**
     * TC06: User ID exceeding 64 characters throws exception
     * <p>
     * Given: User ID with 65 characters
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * And: Message contains max length info
     * </p>
     */
    @Test
    @DisplayName("TC06: User ID exceeding 64 characters throws exception")
    void auth0UserId_exceedsMaxLength_throwsException() {
        // Given: 65 characters (exceeds 64 max)
        String tooLongUserId = "auth0|" + "a".repeat(60); // 6 + 60 = 66 total

        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(tooLongUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed 64 characters")
            .hasMessageContaining("Got: 66");
    }

    /**
     * TC07: User ID with exactly 64 characters accepted
     * <p>
     * Given: User ID with exactly 64 characters
     * When: Create Auth0UserId
     * Then: Creation succeeds (boundary condition)
     * </p>
     */
    @Test
    @DisplayName("TC07: User ID with exactly 64 characters accepted (boundary)")
    void auth0UserId_exactly64Characters_accepted() {
        // Given: Exactly 64 characters (5 + 1 + 58 = 64: "auth0" + "|" + 58 chars)
        String maxLengthUserId = "auth0|" + "a".repeat(58);
        assertThat(maxLengthUserId.length()).isEqualTo(64);

        // When
        Auth0UserId userId = Auth0UserId.of(maxLengthUserId);

        // Then
        assertThat(userId).isNotNull();
        assertThat(userId.value()).isEqualTo(maxLengthUserId);
    }

    /**
     * TC08: Invalid format - missing separator throws exception
     * <p>
     * Given: User ID without pipe separator
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @Test
    @DisplayName("TC08: Invalid format - missing separator throws exception")
    void auth0UserId_missingSeparator_throwsException() {
        // Given
        String invalidUserId = "auth0abc123"; // Missing pipe

        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}")
            .hasMessageContaining(invalidUserId);
    }

    /**
     * TC09: Invalid format - missing provider throws exception
     * <p>
     * Given: User ID without provider (starts with pipe)
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @Test
    @DisplayName("TC09: Invalid format - missing provider throws exception")
    void auth0UserId_missingProvider_throwsException() {
        // Given
        String invalidUserId = "|abc123"; // Missing provider

        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}");
    }

    /**
     * TC10: Invalid format - missing identifier throws exception
     * <p>
     * Given: User ID without identifier (ends with pipe)
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @Test
    @DisplayName("TC10: Invalid format - missing identifier throws exception")
    void auth0UserId_missingIdentifier_throwsException() {
        // Given
        String invalidUserId = "auth0|"; // Missing identifier

        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}");
    }

    /**
     * TC11: Invalid format - special characters in provider throws exception
     * <p>
     * Given: User ID with special characters in provider
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "auth$0|abc123",
        "auth@0|abc123",
        "auth 0|abc123",
        "auth.0|abc123"
    })
    @DisplayName("TC11: Invalid format - special characters in provider throws exception")
    void auth0UserId_invalidProviderCharacters_throwsException(String invalidUserId) {
        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}");
    }

    /**
     * TC12: Invalid format - special characters in identifier throws exception
     * <p>
     * Given: User ID with special characters in identifier
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "auth0|abc@123",
        "auth0|abc 123",
        "auth0|abc.123",
        "auth0|abc-123"
    })
    @DisplayName("TC12: Invalid format - special characters in identifier throws exception")
    void auth0UserId_invalidIdentifierCharacters_throwsException(String invalidUserId) {
        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}");
    }

    /**
     * TC13: Invalid format - multiple separators throws exception
     * <p>
     * Given: User ID with multiple pipe separators
     * When: Create Auth0UserId
     * Then: IllegalArgumentException thrown
     * </p>
     */
    @Test
    @DisplayName("TC13: Invalid format - multiple separators throws exception")
    void auth0UserId_multipleSeparators_throwsException() {
        // Given
        String invalidUserId = "auth0|sub|abc123"; // Multiple pipes

        // When/Then
        assertThatThrownBy(() -> Auth0UserId.of(invalidUserId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match format {provider}|{alphanumeric}");
    }

    /**
     * TC14: Equality - same value returns equal
     * <p>
     * Given: Two Auth0UserId instances with same value
     * When: Compare with equals()
     * Then: Returns true
     * </p>
     */
    @Test
    @DisplayName("TC14: Equality - same value returns equal")
    void auth0UserId_sameValue_equalsReturnsTrue() {
        // Given
        String userId = "auth0|abc123";
        Auth0UserId userId1 = Auth0UserId.of(userId);
        Auth0UserId userId2 = Auth0UserId.of(userId);

        // When/Then
        assertThat(userId1).isEqualTo(userId2);
        assertThat(userId1.hashCode()).isEqualTo(userId2.hashCode());
    }

    /**
     * TC15: Equality - different value returns not equal
     * <p>
     * Given: Two Auth0UserId instances with different values
     * When: Compare with equals()
     * Then: Returns false
     * </p>
     */
    @Test
    @DisplayName("TC15: Equality - different value returns not equal")
    void auth0UserId_differentValue_equalsReturnsFalse() {
        // Given
        Auth0UserId userId1 = Auth0UserId.of("auth0|abc123");
        Auth0UserId userId2 = Auth0UserId.of("auth0|xyz789");

        // When/Then
        assertThat(userId1).isNotEqualTo(userId2);
    }

    /**
     * TC16: Provider extraction with hyphenated provider
     * <p>
     * Given: User ID with hyphenated provider (google-oauth2)
     * When: Extract provider
     * Then: Full provider name returned including hyphens
     * </p>
     */
    @Test
    @DisplayName("TC16: Provider extraction with hyphenated provider")
    void auth0UserId_hyphenatedProvider_extractedCorrectly() {
        // Given
        Auth0UserId userId = Auth0UserId.of("google-oauth2|123");

        // When
        String provider = userId.provider();
        String identifier = userId.identifier();

        // Then
        assertThat(provider).isEqualTo("google-oauth2");
        assertThat(identifier).isEqualTo("123");
    }

    /**
     * TC17: isDatabaseConnection - non-auth0 provider returns false
     * <p>
     * Given: User ID with non-auth0 provider
     * When: Check isDatabaseConnection()
     * Then: Returns false
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "google-oauth2|123",
        "windowslive|abc",
        "facebook|xyz",
        "github|789"
    })
    @DisplayName("TC17: isDatabaseConnection - non-auth0 provider returns false")
    void auth0UserId_nonAuth0Provider_isDatabaseConnectionReturnsFalse(String userId) {
        // Given
        Auth0UserId auth0UserId = Auth0UserId.of(userId);

        // When
        boolean isDatabaseConnection = auth0UserId.isDatabaseConnection();

        // Then
        assertThat(isDatabaseConnection).isFalse();
    }

    /**
     * TC18: toString returns original value
     * <p>
     * Given: Auth0UserId instance
     * When: Call toString()
     * Then: Returns original input value
     * </p>
     */
    @Test
    @DisplayName("TC18: toString returns original value")
    void auth0UserId_toString_returnsOriginalValue() {
        // Given
        String userId = "auth0|507f1f77bcf86cd799439011";
        Auth0UserId auth0UserId = Auth0UserId.of(userId);

        // When
        String toStringValue = auth0UserId.toString();

        // Then
        assertThat(toStringValue).isEqualTo(userId);
    }
}
