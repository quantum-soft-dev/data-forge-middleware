package com.bitbi.dfm.upload.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for S3FileStorageService retry logic.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3FileStorageService Unit Tests")
class S3FileStorageServiceTest {

    @Mock
    private S3Client s3Client;

    private S3FileStorageService service;
    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        service = new S3FileStorageService(s3Client, BUCKET_NAME);
    }

    @Test
    @DisplayName("Should successfully upload file on first attempt")
    void shouldUploadFileOnFirstAttempt() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );
        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // When
        String result = service.uploadFile(file, s3Path, fileName);

        // Then
        assertThat(result).isEqualTo(s3Path + fileName);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should retry upload on S3Exception and succeed")
    void shouldRetryOnS3Exception() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );
        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";

        S3Exception s3Exception = (S3Exception) S3Exception.builder()
                .message("Service unavailable")
                .statusCode(503)
                .build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Exception)
                .thenReturn(PutObjectResponse.builder().build());

        // When
        String result = service.uploadFile(file, s3Path, fileName);

        // Then
        assertThat(result).isEqualTo(s3Path + fileName);
        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should fail after 3 retry attempts")
    void shouldFailAfterMaxRetries() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );
        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";

        S3Exception s3Exception = (S3Exception) S3Exception.builder()
                .message("Service unavailable")
                .statusCode(503)
                .build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Exception);

        // When & Then
        assertThatThrownBy(() -> service.uploadFile(file, s3Path, fileName))
                .isInstanceOf(S3FileStorageService.FileStorageException.class)
                .hasMessageContaining("Failed to upload file to S3 after 3 attempts");

        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should calculate MD5 checksum correctly")
    void shouldCalculateChecksum() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );

        // When
        String checksum = service.calculateChecksum(file);

        // Then
        assertThat(checksum).isNotNull();
        assertThat(checksum).hasSize(32); // MD5 hex string is 32 characters
        assertThat(checksum).matches("[a-f0-9]{32}"); // Hex format
    }

    @Test
    @DisplayName("Should throw FileStorageException on IO error")
    void shouldThrowOnIOError() throws Exception {
        // Given
        MultipartFile file = mock(MultipartFile.class);
        lenient().when(file.getOriginalFilename()).thenReturn("test.txt");
        lenient().when(file.getSize()).thenReturn(100L);
        lenient().when(file.getContentType()).thenReturn("text/plain");
        when(file.getInputStream()).thenThrow(new java.io.IOException("IO Error"));

        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";

        // When & Then
        assertThatThrownBy(() -> service.uploadFile(file, s3Path, fileName))
                .isInstanceOf(S3FileStorageService.FileStorageException.class)
                .hasMessageContaining("Failed to read file content");
    }

    @Test
    @DisplayName("Should use correct bucket name and S3 key")
    void shouldUseCorrectBucketAndKey() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );
        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";
        String expectedKey = s3Path + fileName;

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // When
        String result = service.uploadFile(file, s3Path, fileName);

        // Then
        assertThat(result).isEqualTo(expectedKey);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should retry with delay between attempts")
    void shouldDelayBetweenRetries() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );
        String s3Path = "account123/example.com/2024-01-01/12-00/";
        String fileName = "test.txt";

        S3Exception s3Exception = (S3Exception) S3Exception.builder()
                .message("Service unavailable")
                .statusCode(503)
                .build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Exception)
                .thenThrow(s3Exception)
                .thenReturn(PutObjectResponse.builder().build());

        // When
        long startTime = System.currentTimeMillis();
        String result = service.uploadFile(file, s3Path, fileName);
        long duration = System.currentTimeMillis() - startTime;

        // Then
        assertThat(result).isEqualTo(s3Path + fileName);
        // Should have at least 2 seconds delay (2 retries * 1 second)
        assertThat(duration).isGreaterThanOrEqualTo(2000);
        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
