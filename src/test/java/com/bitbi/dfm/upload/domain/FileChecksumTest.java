package com.bitbi.dfm.upload.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileChecksum value object.
 */
@DisplayName("FileChecksum Value Object Unit Tests")
class FileChecksumTest {

    @Test
    @DisplayName("Should create FileChecksum with valid algorithm and value")
    void shouldCreateFileChecksumWithValidAlgorithmAndValue() {
        // Given
        String algorithm = "MD5";
        String value = "5d41402abc4b2a76b9719d911017c592";

        // When
        FileChecksum checksum = new FileChecksum(algorithm, value);

        // Then
        assertNotNull(checksum);
        assertEquals(algorithm, checksum.algorithm());
        assertEquals(value, checksum.value());
    }

    @Test
    @DisplayName("Should create FileChecksum with SHA-256 algorithm")
    void shouldCreateFileChecksumWithSha256Algorithm() {
        // Given
        String algorithm = "SHA-256";
        String value = "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae";

        // When
        FileChecksum checksum = new FileChecksum(algorithm, value);

        // Then
        assertEquals(algorithm, checksum.algorithm());
        assertEquals(value, checksum.value());
    }

    @Test
    @DisplayName("Should throw exception when algorithm is null")
    void shouldThrowExceptionWhenAlgorithmIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                new FileChecksum(null, "5d41402abc4b2a76b9719d911017c592")
        );
    }

    @Test
    @DisplayName("Should throw exception when value is null")
    void shouldThrowExceptionWhenValueIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                new FileChecksum("MD5", null)
        );
    }

    @Test
    @DisplayName("Should throw exception when algorithm is blank")
    void shouldThrowExceptionWhenAlgorithmIsBlank() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new FileChecksum("", "5d41402abc4b2a76b9719d911017c592")
        );
        assertEquals("Algorithm cannot be blank", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when value is blank")
    void shouldThrowExceptionWhenValueIsBlank() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new FileChecksum("MD5", "")
        );
        assertEquals("Checksum value cannot be blank", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when value is not hexadecimal")
    void shouldThrowExceptionWhenValueIsNotHexadecimal() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new FileChecksum("MD5", "not-a-hex-value!")
        );
        assertEquals("Checksum value must be in hexadecimal format", exception.getMessage());
    }

    @Test
    @DisplayName("Should accept uppercase hexadecimal value")
    void shouldAcceptUppercaseHexadecimalValue() {
        // Given
        String value = "5D41402ABC4B2A76B9719D911017C592";

        // When
        FileChecksum checksum = new FileChecksum("MD5", value);

        // Then
        assertEquals(value, checksum.value());
    }

    @Test
    @DisplayName("Should accept mixed case hexadecimal value")
    void shouldAcceptMixedCaseHexadecimalValue() {
        // Given
        String value = "5d41402ABC4B2a76b9719D911017c592";

        // When
        FileChecksum checksum = new FileChecksum("MD5", value);

        // Then
        assertEquals(value, checksum.value());
    }

    @Test
    @DisplayName("Should calculate MD5 checksum for given data")
    void shouldCalculateMd5ChecksumForGivenData() {
        // Given
        byte[] data = "hello".getBytes();

        // When
        FileChecksum checksum = FileChecksum.calculateMD5(data);

        // Then
        assertNotNull(checksum);
        assertEquals("MD5", checksum.algorithm());
        assertEquals("5d41402abc4b2a76b9719d911017c592", checksum.value());
    }

    @Test
    @DisplayName("Should calculate MD5 for empty data")
    void shouldCalculateMd5ForEmptyData() {
        // Given
        byte[] data = new byte[0];

        // When
        FileChecksum checksum = FileChecksum.calculateMD5(data);

        // Then
        assertNotNull(checksum);
        assertEquals("MD5", checksum.algorithm());
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", checksum.value());
    }

    @Test
    @DisplayName("Should throw exception when calculating MD5 for null data")
    void shouldThrowExceptionWhenCalculatingMd5ForNullData() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                FileChecksum.calculateMD5(null)
        );
    }

    @Test
    @DisplayName("Should verify checksum matches data")
    void shouldVerifyChecksumMatchesData() {
        // Given
        byte[] data = "hello".getBytes();
        FileChecksum checksum = FileChecksum.calculateMD5(data);

        // When
        boolean isValid = checksum.verify(data);

        // Then
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Should verify checksum does not match different data")
    void shouldVerifyChecksumDoesNotMatchDifferentData() {
        // Given
        byte[] originalData = "hello".getBytes();
        byte[] differentData = "world".getBytes();
        FileChecksum checksum = FileChecksum.calculateMD5(originalData);

        // When
        boolean isValid = checksum.verify(differentData);

        // Then
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should verify with case insensitive comparison")
    void shouldVerifyWithCaseInsensitiveComparison() {
        // Given
        byte[] data = "hello".getBytes();
        FileChecksum checksum = new FileChecksum("MD5", "5D41402ABC4B2A76B9719D911017C592");

        // When
        boolean isValid = checksum.verify(data);

        // Then
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Should create FileChecksum from hex value")
    void shouldCreateFileChecksumFromHexValue() {
        // Given
        String hexValue = "5d41402abc4b2a76b9719d911017c592";

        // When
        FileChecksum checksum = FileChecksum.fromHex(hexValue);

        // Then
        assertNotNull(checksum);
        assertEquals("MD5", checksum.algorithm());
        assertEquals(hexValue, checksum.value());
    }

    @Test
    @DisplayName("Should calculate different checksums for different data")
    void shouldCalculateDifferentChecksumsForDifferentData() {
        // Given
        byte[] data1 = "hello".getBytes();
        byte[] data2 = "world".getBytes();

        // When
        FileChecksum checksum1 = FileChecksum.calculateMD5(data1);
        FileChecksum checksum2 = FileChecksum.calculateMD5(data2);

        // Then
        assertNotEquals(checksum1.value(), checksum2.value());
    }

    @Test
    @DisplayName("Should calculate same checksum for same data")
    void shouldCalculateSameChecksumForSameData() {
        // Given
        byte[] data1 = "hello".getBytes();
        byte[] data2 = "hello".getBytes();

        // When
        FileChecksum checksum1 = FileChecksum.calculateMD5(data1);
        FileChecksum checksum2 = FileChecksum.calculateMD5(data2);

        // Then
        assertEquals(checksum1.value(), checksum2.value());
    }

    @Test
    @DisplayName("Should calculate MD5 for large data")
    void shouldCalculateMd5ForLargeData() {
        // Given
        byte[] largeData = new byte[1024 * 1024]; // 1MB of zeros

        // When
        FileChecksum checksum = FileChecksum.calculateMD5(largeData);

        // Then
        assertNotNull(checksum);
        assertEquals("MD5", checksum.algorithm());
        assertNotNull(checksum.value());
        assertTrue(checksum.value().matches("^[0-9a-f]+$"));
    }

    @Test
    @DisplayName("Should use record equality correctly")
    void shouldUseRecordEqualityCorrectly() {
        // Given
        FileChecksum checksum1 = new FileChecksum("MD5", "5d41402abc4b2a76b9719d911017c592");
        FileChecksum checksum2 = new FileChecksum("MD5", "5d41402abc4b2a76b9719d911017c592");
        FileChecksum checksum3 = new FileChecksum("MD5", "ffffffffffffffffffffffffffffffff");

        // Then
        assertEquals(checksum1, checksum2);
        assertNotEquals(checksum1, checksum3);
    }

    @Test
    @DisplayName("Should use record hashCode correctly")
    void shouldUseRecordHashCodeCorrectly() {
        // Given
        FileChecksum checksum1 = new FileChecksum("MD5", "5d41402abc4b2a76b9719d911017c592");
        FileChecksum checksum2 = new FileChecksum("MD5", "5d41402abc4b2a76b9719d911017c592");

        // Then
        assertEquals(checksum1.hashCode(), checksum2.hashCode());
    }

    @Test
    @DisplayName("Should throw exception when algorithm is whitespace")
    void shouldThrowExceptionWhenAlgorithmIsWhitespace() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new FileChecksum("   ", "5d41402abc4b2a76b9719d911017c592")
        );
        assertEquals("Algorithm cannot be blank", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when value is whitespace")
    void shouldThrowExceptionWhenValueIsWhitespace() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new FileChecksum("MD5", "   ")
        );
        assertEquals("Checksum value cannot be blank", exception.getMessage());
    }
}
