package com.bitbi.dfm.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Company Value Object.
 */
@DisplayName("Company Value Object Tests")
class CompanyTest {

    @Test
    @DisplayName("Should create valid company name")
    void shouldCreateValidCompanyName() {
        // Given: Valid company name
        String validCompany = "Acme Corporation";

        // When: Create Company
        Company company = Company.of(validCompany);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(validCompany, company.getValue());
        assertFalse(company.isEmpty());
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNullInput() {
        // When: Create Company with null
        Company company = Company.of(null);

        // Then: Null returned
        assertNull(company);
    }

    @Test
    @DisplayName("Should return null for blank input")
    void shouldReturnNullForBlankInput() {
        // When: Create Company with blank string
        Company company = Company.of("   ");

        // Then: Null returned
        assertNull(company);
    }

    @Test
    @DisplayName("Should trim whitespace")
    void shouldTrimWhitespace() {
        // Given: Company name with whitespace
        String companyWithWhitespace = "  Acme Corp  ";

        // When: Create Company
        Company company = Company.of(companyWithWhitespace);

        // Then: Whitespace trimmed
        assertNotNull(company);
        assertEquals("Acme Corp", company.getValue());
    }

    @Test
    @DisplayName("Should accept minimum length company name")
    void shouldAcceptMinimumLengthCompanyName() {
        // Given: Company name with exactly 2 characters (minimum)
        String minLengthCompany = "AB";

        // When: Create Company
        Company company = Company.of(minLengthCompany);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(minLengthCompany, company.getValue());
    }

    @Test
    @DisplayName("Should accept maximum length company name")
    void shouldAcceptMaximumLengthCompanyName() {
        // Given: Company name with exactly 255 characters (maximum)
        String maxLengthCompany = "A".repeat(255);

        // When: Create Company
        Company company = Company.of(maxLengthCompany);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(maxLengthCompany, company.getValue());
    }

    @Test
    @DisplayName("Should reject company name too short")
    void shouldRejectCompanyNameTooShort() {
        // Given: Company name with only 1 character (min is 2)
        String tooShortCompany = "A";

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Company.of(tooShortCompany)
        );
        assertTrue(exception.getMessage().contains("must be at least 2 characters long"));
    }

    @Test
    @DisplayName("Should reject company name too long")
    void shouldRejectCompanyNameTooLong() {
        // Given: Company name exceeding max length (256 characters)
        String tooLongCompany = "A".repeat(256);

        // When/Then: Exception thrown
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Company.of(tooLongCompany)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum length of 255"));
    }

    @Test
    @DisplayName("Should accept company name with special characters")
    void shouldAcceptCompanyNameWithSpecialCharacters() {
        // Given: Company name with special characters
        String companyWithSpecialChars = "O'Reilly & Associates, Inc.";

        // When: Create Company
        Company company = Company.of(companyWithSpecialChars);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(companyWithSpecialChars, company.getValue());
    }

    @Test
    @DisplayName("Should accept company name with unicode characters")
    void shouldAcceptCompanyNameWithUnicodeCharacters() {
        // Given: Company name with unicode characters
        String companyWithUnicode = "Société Générale";

        // When: Create Company
        Company company = Company.of(companyWithUnicode);

        // Then: Company created successfully
        assertNotNull(company);
        assertEquals(companyWithUnicode, company.getValue());
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        // Given: Two companies with same value
        Company company1 = Company.of("Acme Corp");
        Company company2 = Company.of("Acme Corp");
        Company company3 = Company.of("Beta Inc");

        // Then: Equals works correctly
        assertEquals(company1, company2);
        assertNotEquals(company1, company3);
        assertNotEquals(company1, null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        // Given: Two companies with same value
        Company company1 = Company.of("Acme Corp");
        Company company2 = Company.of("Acme Corp");

        // Then: Hash codes are equal
        assertEquals(company1.hashCode(), company2.hashCode());
    }

    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        // Given: Company with value
        Company company = Company.of("Acme Corp");

        // Then: toString returns value
        assertEquals("Acme Corp", company.toString());
    }
}
