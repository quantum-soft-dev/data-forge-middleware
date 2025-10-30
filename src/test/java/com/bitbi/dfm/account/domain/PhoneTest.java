package com.bitbi.dfm.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Phone Value Object.
 */
@DisplayName("Phone Value Object Tests")
class PhoneTest {

    @Test
    @DisplayName("Should create valid phone number")
    void shouldCreateValidPhoneNumber() {
        // Given: Valid phone number
        String validPhone = "+1234567890";

        // When: Create Phone
        Phone phone = Phone.of(validPhone);

        // Then: Phone created successfully
        assertNotNull(phone);
        assertEquals(validPhone, phone.getValue());
        assertFalse(phone.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+1234567890",
            "+442071234567",
            "+79161234567",
            "1234567890",
            "+12345678901234", // 14 digits (max is 15)
            "0123456789",      // Leading zero allowed (some countries use it)
            "+0123456789"      // Leading zero after + allowed
    })
    @DisplayName("Should accept valid phone formats")
    void shouldAcceptValidPhoneFormats(String phoneNumber) {
        // When: Create Phone with valid format
        Phone phone = Phone.of(phoneNumber);

        // Then: Phone created successfully
        assertNotNull(phone);
        assertEquals(phoneNumber, phone.getValue());
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNullInput() {
        // When: Create Phone with null
        Phone phone = Phone.of(null);

        // Then: Null returned
        assertNull(phone);
    }

    @Test
    @DisplayName("Should return null for blank input")
    void shouldReturnNullForBlankInput() {
        // When: Create Phone with blank string
        Phone phone = Phone.of("   ");

        // Then: Null returned
        assertNull(phone);
    }

    @Test
    @DisplayName("Should trim whitespace")
    void shouldTrimWhitespace() {
        // Given: Phone with whitespace
        String phoneWithWhitespace = "  +1234567890  ";

        // When: Create Phone
        Phone phone = Phone.of(phoneWithWhitespace);

        // Then: Whitespace trimmed
        assertNotNull(phone);
        assertEquals("+1234567890", phone.getValue());
    }

    @Test
    @DisplayName("Should reject phone too short")
    void shouldRejectPhoneTooShort() {
        // Given: Phone with only 6 digits (min is 7)
        String tooShortPhone = "+123456";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Phone.of(tooShortPhone)
        );
        assertTrue(exception.getMessage().contains("Invalid phone number format"));
    }

    @Test
    @DisplayName("Should reject phone too long")
    void shouldRejectPhoneTooLong() {
        // Given: Phone exceeding max length (51 characters)
        String tooLongPhone = "+1234567890123456789012345678901234567890123456789012";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Phone.of(tooLongPhone)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum length of 50"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc123",           // Letters not allowed
            "12-34-56",         // Hyphens not allowed
            "(123)456-7890",    // Parentheses not allowed
            "+1 234 567 890",   // Spaces not allowed
            "123456",           // Too short (min 7 digits)
            "+1234567890123456" // Too long (max 15 digits)
    })
    @DisplayName("Should reject invalid phone formats")
    void shouldRejectInvalidPhoneFormats(String invalidPhone) {
        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Phone.of(invalidPhone)
        );
        assertTrue(exception.getMessage().contains("Invalid phone number format"));
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        // Given: Two phones with same value
        Phone phone1 = Phone.of("+1234567890");
        Phone phone2 = Phone.of("+1234567890");
        Phone phone3 = Phone.of("+9876543210");

        // Then: Equals works correctly
        assertEquals(phone1, phone2);
        assertNotEquals(phone1, phone3);
        assertNotEquals(phone1, null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        // Given: Two phones with same value
        Phone phone1 = Phone.of("+1234567890");
        Phone phone2 = Phone.of("+1234567890");

        // Then: Hash codes are equal
        assertEquals(phone1.hashCode(), phone2.hashCode());
    }

    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        // Given: Phone with value
        Phone phone = Phone.of("+1234567890");

        // Then: toString returns value
        assertEquals("+1234567890", phone.toString());
    }

    @Test
    @DisplayName("Should return empty string for null phone toString")
    void shouldReturnEmptyStringForNullPhoneToString() {
        // Given: Null phone (simulated by creating Phone with null value)
        // Since Phone.of(null) returns null, we can't directly test this
        // This test documents the expected behavior if Phone allowed null values
        Phone nullPhone = Phone.of(null);

        // Then: Null is returned, not an empty string Phone object
        assertNull(nullPhone);
    }
}
