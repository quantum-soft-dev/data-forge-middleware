package com.bitbi.dfm.upload.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UploadedFile entity.
 */
@DisplayName("UploadedFile Entity Unit Tests")
class UploadedFileTest {

    private final UUID testBatchId = UUID.randomUUID();
    private final FileChecksum testChecksum = FileChecksum.fromHex("5d41402abc4b2a76b9719d911017c592");

    @Test
    @DisplayName("Should create UploadedFile with all fields")
    void shouldCreateUploadedFileWithAllFields() {
        // Given
        String originalFileName = "data.csv";
        String s3Path = "uploads/batch-123/";
        Long fileSize = 1024L;
        String contentType = "text/csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, originalFileName, s3Path,
                fileSize, contentType, testChecksum);

        // Then
        assertNotNull(file);
        assertNotNull(file.getId());
        assertEquals(testBatchId, file.getBatchId());
        assertEquals(originalFileName, file.getOriginalFileName());
        assertEquals(s3Path + originalFileName, file.getS3Key());
        assertEquals(fileSize, file.getFileSize());
        assertEquals(contentType, file.getContentType());
        assertEquals(testChecksum.value(), file.getChecksum());
        assertNotNull(file.getUploadedAt());
    }

    @Test
    @DisplayName("Should throw exception when batchId is null")
    void shouldThrowExceptionWhenBatchIdIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(null, "file.csv", "path/", 1024L, "text/csv", testChecksum)
        );
    }

    @Test
    @DisplayName("Should throw exception when originalFileName is null")
    void shouldThrowExceptionWhenOriginalFileNameIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(testBatchId, null, "path/", 1024L, "text/csv", testChecksum)
        );
    }

    @Test
    @DisplayName("Should throw exception when s3Path is null")
    void shouldThrowExceptionWhenS3PathIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", null, 1024L, "text/csv", testChecksum)
        );
    }

    @Test
    @DisplayName("Should throw exception when fileSize is null")
    void shouldThrowExceptionWhenFileSizeIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", "path/", null, "text/csv", testChecksum)
        );
    }

    @Test
    @DisplayName("Should throw exception when contentType is null")
    void shouldThrowExceptionWhenContentTypeIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", "path/", 1024L, null, testChecksum)
        );
    }

    @Test
    @DisplayName("Should throw exception when fileChecksum is null")
    void shouldThrowExceptionWhenFileChecksumIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", "path/", 1024L, "text/csv", null)
        );
    }

    @Test
    @DisplayName("Should throw exception when fileSize is zero")
    void shouldThrowExceptionWhenFileSizeIsZero() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", "path/", 0L, "text/csv", testChecksum)
        );
        assertEquals("FileSize must be positive", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when fileSize is negative")
    void shouldThrowExceptionWhenFileSizeIsNegative() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                UploadedFile.create(testBatchId, "file.csv", "path/", -1L, "text/csv", testChecksum)
        );
        assertEquals("FileSize must be positive", exception.getMessage());
    }

    @Test
    @DisplayName("Should generate unique IDs for each UploadedFile")
    void shouldGenerateUniqueIdsForEachUploadedFile() {
        // When
        UploadedFile file1 = UploadedFile.create(testBatchId, "file1.csv", "path/",
                1024L, "text/csv", testChecksum);
        UploadedFile file2 = UploadedFile.create(testBatchId, "file2.csv", "path/",
                1024L, "text/csv", testChecksum);

        // Then
        assertNotEquals(file1.getId(), file2.getId());
    }

    @Test
    @DisplayName("Should construct S3 key from path and filename")
    void shouldConstructS3KeyFromPathAndFilename() {
        // Given
        String s3Path = "uploads/2025/10/";
        String fileName = "data.csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, fileName, s3Path,
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals("uploads/2025/10/data.csv", file.getS3Key());
    }

    @Test
    @DisplayName("Should set uploadedAt to current time")
    void shouldSetUploadedAtToCurrentTime() {
        // Given
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        // When
        UploadedFile file = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", testChecksum);

        // Then
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertTrue(file.getUploadedAt().isAfter(before));
        assertTrue(file.getUploadedAt().isBefore(after));
    }

    @Test
    @DisplayName("Should implement equals correctly based on ID")
    void shouldImplementEqualsCorrectlyBasedOnId() {
        // Given
        UploadedFile file1 = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", testChecksum);
        UploadedFile file2 = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals(file1, file1);
        assertNotEquals(file1, file2);
        assertNotEquals(file1, null);
        assertNotEquals(file1, new Object());
    }

    @Test
    @DisplayName("Should implement hashCode correctly based on ID")
    void shouldImplementHashCodeCorrectlyBasedOnId() {
        // Given
        UploadedFile file1 = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", testChecksum);
        UploadedFile file2 = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals(file1.hashCode(), file1.hashCode());
        assertNotEquals(file1.hashCode(), file2.hashCode());
    }

    @Test
    @DisplayName("Should handle various content types")
    void shouldHandleVariousContentTypes() {
        // When
        UploadedFile csvFile = UploadedFile.create(testBatchId, "data.csv", "path/",
                1024L, "text/csv", testChecksum);
        UploadedFile jsonFile = UploadedFile.create(testBatchId, "data.json", "path/",
                1024L, "application/json", testChecksum);
        UploadedFile excelFile = UploadedFile.create(testBatchId, "data.xlsx", "path/",
                1024L, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", testChecksum);

        // Then
        assertEquals("text/csv", csvFile.getContentType());
        assertEquals("application/json", jsonFile.getContentType());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelFile.getContentType());
    }

    @Test
    @DisplayName("Should handle large file sizes")
    void shouldHandleLargeFileSizes() {
        // Given
        Long largeFileSize = 5L * 1024 * 1024 * 1024; // 5GB in bytes

        // When
        UploadedFile file = UploadedFile.create(testBatchId, "large-file.csv", "path/",
                largeFileSize, "text/csv", testChecksum);

        // Then
        assertEquals(largeFileSize, file.getFileSize());
    }

    @Test
    @DisplayName("Should handle long file names")
    void shouldHandleLongFileNames() {
        // Given
        String longFileName = "a".repeat(200) + ".csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, longFileName, "path/",
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals(longFileName, file.getOriginalFileName());
    }

    @Test
    @DisplayName("Should handle complex S3 paths")
    void shouldHandleComplexS3Paths() {
        // Given
        String complexPath = "uploads/account-123/site-456/batch-789/2025/10/07/";
        String fileName = "data.csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, fileName, complexPath,
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals(complexPath + fileName, file.getS3Key());
    }

    @Test
    @DisplayName("Should store checksum value from FileChecksum")
    void shouldStoreChecksumValueFromFileChecksum() {
        // Given
        FileChecksum customChecksum = FileChecksum.fromHex("ffffffffffffffffffffffffffffffff");

        // When
        UploadedFile file = UploadedFile.create(testBatchId, "file.csv", "path/",
                1024L, "text/csv", customChecksum);

        // Then
        assertEquals("ffffffffffffffffffffffffffffffff", file.getChecksum());
    }

    @Test
    @DisplayName("Should handle file names with special characters")
    void shouldHandleFileNamesWithSpecialCharacters() {
        // Given
        String fileName = "my-data_file (1).csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, fileName, "path/",
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals(fileName, file.getOriginalFileName());
        assertTrue(file.getS3Key().endsWith(fileName));
    }

    @Test
    @DisplayName("Should handle S3 path without trailing slash")
    void shouldHandleS3PathWithoutTrailingSlash() {
        // Given
        String s3Path = "uploads/batch-123";
        String fileName = "data.csv";

        // When
        UploadedFile file = UploadedFile.create(testBatchId, fileName, s3Path,
                1024L, "text/csv", testChecksum);

        // Then
        assertEquals("uploads/batch-123data.csv", file.getS3Key());
    }

    @Test
    @DisplayName("Should accept minimum valid file size")
    void shouldAcceptMinimumValidFileSize() {
        // Given
        Long minFileSize = 1L;

        // When
        UploadedFile file = UploadedFile.create(testBatchId, "file.csv", "path/",
                minFileSize, "text/csv", testChecksum);

        // Then
        assertEquals(minFileSize, file.getFileSize());
    }
}
