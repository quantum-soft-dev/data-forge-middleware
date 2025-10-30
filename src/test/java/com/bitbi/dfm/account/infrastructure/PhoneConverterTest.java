package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Phone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhoneConverter JPA AttributeConverter.
 */
@DisplayName("PhoneConverter Unit Tests")
class PhoneConverterTest {

    private PhoneConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PhoneConverter();
    }

    @Test
    @DisplayName("convertToDatabaseColumn should convert Phone to String")
    void convertToDatabaseColumn_shouldConvertPhoneToString() {
        // Given: Phone value object
        Phone phone = Phone.of("+1234567890");

        // When: Convert to database column
        String dbValue = converter.convertToDatabaseColumn(phone);

        // Then: String value returned
        assertNotNull(dbValue);
        assertEquals("+1234567890", dbValue);
    }

    @Test
    @DisplayName("convertToDatabaseColumn should return null for null Phone")
    void convertToDatabaseColumn_shouldReturnNullForNullPhone() {
        // When: Convert null Phone to database column
        String dbValue = converter.convertToDatabaseColumn(null);

        // Then: Null returned
        assertNull(dbValue);
    }

    @Test
    @DisplayName("convertToEntityAttribute should convert String to Phone")
    void convertToEntityAttribute_shouldConvertStringToPhone() {
        // Given: Database string value
        String dbValue = "+1234567890";

        // When: Convert to entity attribute
        Phone phone = converter.convertToEntityAttribute(dbValue);

        // Then: Phone value object created
        assertNotNull(phone);
        assertEquals("+1234567890", phone.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null for null String")
    void convertToEntityAttribute_shouldReturnNullForNullString() {
        // When: Convert null String to entity attribute
        Phone phone = converter.convertToEntityAttribute(null);

        // Then: Null returned
        assertNull(phone);
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null for blank String")
    void convertToEntityAttribute_shouldReturnNullForBlankString() {
        // When: Convert blank String to entity attribute
        Phone phone = converter.convertToEntityAttribute("   ");

        // Then: Null returned
        assertNull(phone);
    }

    @Test
    @DisplayName("convertToEntityAttribute should trim whitespace")
    void convertToEntityAttribute_shouldTrimWhitespace() {
        // Given: Database value with whitespace
        String dbValue = "  +1234567890  ";

        // When: Convert to entity attribute
        Phone phone = converter.convertToEntityAttribute(dbValue);

        // Then: Whitespace trimmed
        assertNotNull(phone);
        assertEquals("+1234567890", phone.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should throw exception for invalid phone format")
    void convertToEntityAttribute_shouldThrowExceptionForInvalidFormat() {
        // Given: Invalid phone format
        String invalidPhone = "abc123";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute(invalidPhone)
        );
        assertTrue(exception.getMessage().contains("Invalid phone number format"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should throw exception for phone exceeding max length")
    void convertToEntityAttribute_shouldThrowExceptionForPhoneTooLong() {
        // Given: Phone exceeding max length (51 characters)
        String tooLongPhone = "+1234567890123456789012345678901234567890123456789012";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute(tooLongPhone)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum length of 50"));
    }

    @Test
    @DisplayName("Round-trip conversion should preserve value")
    void roundTripConversion_shouldPreserveValue() {
        // Given: Original phone value
        Phone originalPhone = Phone.of("+442071234567");

        // When: Convert to database and back to entity
        String dbValue = converter.convertToDatabaseColumn(originalPhone);
        Phone roundTripPhone = converter.convertToEntityAttribute(dbValue);

        // Then: Value preserved
        assertNotNull(roundTripPhone);
        assertEquals(originalPhone.getValue(), roundTripPhone.getValue());
        assertEquals(originalPhone, roundTripPhone);
    }

    @Test
    @DisplayName("Round-trip conversion should handle null")
    void roundTripConversion_shouldHandleNull() {
        // When: Convert null to database and back to entity
        String dbValue = converter.convertToDatabaseColumn(null);
        Phone roundTripPhone = converter.convertToEntityAttribute(dbValue);

        // Then: Null preserved
        assertNull(dbValue);
        assertNull(roundTripPhone);
    }

    @Test
    @DisplayName("Converter should be idempotent for valid values")
    void converter_shouldBeIdempotent() {
        // Given: Phone value
        Phone originalPhone = Phone.of("+79161234567");

        // When: Convert multiple times
        String dbValue1 = converter.convertToDatabaseColumn(originalPhone);
        Phone phone1 = converter.convertToEntityAttribute(dbValue1);
        String dbValue2 = converter.convertToDatabaseColumn(phone1);
        Phone phone2 = converter.convertToEntityAttribute(dbValue2);

        // Then: Value remains consistent
        assertEquals(dbValue1, dbValue2);
        assertEquals(phone1, phone2);
        assertEquals(originalPhone, phone2);
    }
}
