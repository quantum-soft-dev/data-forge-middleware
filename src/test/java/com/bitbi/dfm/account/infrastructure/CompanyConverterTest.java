package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Company;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CompanyConverter JPA AttributeConverter.
 */
@DisplayName("CompanyConverter Unit Tests")
class CompanyConverterTest {

    private CompanyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CompanyConverter();
    }

    @Test
    @DisplayName("convertToDatabaseColumn should convert Company to String")
    void convertToDatabaseColumn_shouldConvertCompanyToString() {
        // Given: Company value object
        Company company = Company.of("Acme Corp");

        // When: Convert to database column
        String dbValue = converter.convertToDatabaseColumn(company);

        // Then: String value returned
        assertNotNull(dbValue);
        assertEquals("Acme Corp", dbValue);
    }

    @Test
    @DisplayName("convertToDatabaseColumn should return null for null Company")
    void convertToDatabaseColumn_shouldReturnNullForNullCompany() {
        // When: Convert null Company to database column
        String dbValue = converter.convertToDatabaseColumn(null);

        // Then: Null returned
        assertNull(dbValue);
    }

    @Test
    @DisplayName("convertToEntityAttribute should convert String to Company")
    void convertToEntityAttribute_shouldConvertStringToCompany() {
        // Given: Database string value
        String dbValue = "Tech Solutions Ltd";

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(dbValue);

        // Then: Company value object created
        assertNotNull(company);
        assertEquals("Tech Solutions Ltd", company.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null for null String")
    void convertToEntityAttribute_shouldReturnNullForNullString() {
        // When: Convert null String to entity attribute
        Company company = converter.convertToEntityAttribute(null);

        // Then: Null returned
        assertNull(company);
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null for blank String")
    void convertToEntityAttribute_shouldReturnNullForBlankString() {
        // When: Convert blank String to entity attribute
        Company company = converter.convertToEntityAttribute("   ");

        // Then: Null returned
        assertNull(company);
    }

    @Test
    @DisplayName("convertToEntityAttribute should trim whitespace")
    void convertToEntityAttribute_shouldTrimWhitespace() {
        // Given: Database value with whitespace
        String dbValue = "  Global Industries  ";

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(dbValue);

        // Then: Whitespace trimmed
        assertNotNull(company);
        assertEquals("Global Industries", company.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should throw exception for company name too short")
    void convertToEntityAttribute_shouldThrowExceptionForCompanyTooShort() {
        // Given: Company name with only 1 character (min is 2)
        String tooShort = "A";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute(tooShort)
        );
        assertTrue(exception.getMessage().contains("must be at least"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should throw exception for company name exceeding max length")
    void convertToEntityAttribute_shouldThrowExceptionForCompanyTooLong() {
        // Given: Company name exceeding max length (256 characters)
        String tooLong = "A".repeat(256);

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute(tooLong)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum length"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should accept company name at min length")
    void convertToEntityAttribute_shouldAcceptCompanyAtMinLength() {
        // Given: Company name with exactly 2 characters (min length)
        String minLength = "AB";

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(minLength);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals("AB", company.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should accept company name at max length")
    void convertToEntityAttribute_shouldAcceptCompanyAtMaxLength() {
        // Given: Company name with exactly 255 characters (max length)
        String maxLength = "A".repeat(255);

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(maxLength);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(255, company.getValue().length());
    }

    @Test
    @DisplayName("convertToEntityAttribute should accept Unicode characters")
    void convertToEntityAttribute_shouldAcceptUnicodeCharacters() {
        // Given: Company name with Unicode characters
        String unicodeName = "株式会社テクノロジー";

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(unicodeName);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(unicodeName, company.getValue());
    }

    @Test
    @DisplayName("convertToEntityAttribute should accept special characters")
    void convertToEntityAttribute_shouldAcceptSpecialCharacters() {
        // Given: Company name with special characters
        String specialChars = "Tech & Co. (UK) Ltd.";

        // When: Convert to entity attribute
        Company company = converter.convertToEntityAttribute(specialChars);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(specialChars, company.getValue());
    }

    @Test
    @DisplayName("Round-trip conversion should preserve value")
    void roundTripConversion_shouldPreserveValue() {
        // Given: Original company value
        Company originalCompany = Company.of("DataForge Solutions Inc.");

        // When: Convert to database and back to entity
        String dbValue = converter.convertToDatabaseColumn(originalCompany);
        Company roundTripCompany = converter.convertToEntityAttribute(dbValue);

        // Then: Value preserved
        assertNotNull(roundTripCompany);
        assertEquals(originalCompany.getValue(), roundTripCompany.getValue());
        assertEquals(originalCompany, roundTripCompany);
    }

    @Test
    @DisplayName("Round-trip conversion should handle null")
    void roundTripConversion_shouldHandleNull() {
        // When: Convert null to database and back to entity
        String dbValue = converter.convertToDatabaseColumn(null);
        Company roundTripCompany = converter.convertToEntityAttribute(dbValue);

        // Then: Null preserved
        assertNull(dbValue);
        assertNull(roundTripCompany);
    }

    @Test
    @DisplayName("Converter should be idempotent for valid values")
    void converter_shouldBeIdempotent() {
        // Given: Company value
        Company originalCompany = Company.of("Innovation Labs Ltd");

        // When: Convert multiple times
        String dbValue1 = converter.convertToDatabaseColumn(originalCompany);
        Company company1 = converter.convertToEntityAttribute(dbValue1);
        String dbValue2 = converter.convertToDatabaseColumn(company1);
        Company company2 = converter.convertToEntityAttribute(dbValue2);

        // Then: Value remains consistent
        assertEquals(dbValue1, dbValue2);
        assertEquals(company1, company2);
        assertEquals(originalCompany, company2);
    }
}
