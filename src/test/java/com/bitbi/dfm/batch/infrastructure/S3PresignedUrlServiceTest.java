package com.bitbi.dfm.batch.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3PresignedUrlService} expiry handling (028-parquet-export-plugin, T003).
 */
@ExtendWith(MockitoExtension.class)
class S3PresignedUrlServiceTest {

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedGetObjectRequest presignedRequest;

    private S3PresignedUrlService service;

    @BeforeEach
    void setUp() throws MalformedURLException {
        service = new S3PresignedUrlService(s3Presigner, "test-bucket");
        URL url = URI.create("https://s3.example.com/test-bucket/key?sig=abc").toURL();
        when(presignedRequest.url()).thenReturn(url);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);
    }

    @Test
    @DisplayName("Should use 15-minute expiry for default generatePresignedUrl")
    void shouldUseDefaultExpiry() {
        service.generatePresignedUrl("some/key.parquet", "file.parquet");

        GetObjectPresignRequest request = capturedPresignRequest();
        assertEquals(Duration.ofMinutes(15), request.signatureDuration());
    }

    @Test
    @DisplayName("Should use custom expiry when provided")
    void shouldUseCustomExpiry() {
        service.generatePresignedUrl("some/key.parquet", "file.parquet", Duration.ofSeconds(60));

        GetObjectPresignRequest request = capturedPresignRequest();
        assertEquals(Duration.ofSeconds(60), request.signatureDuration());
    }

    @Test
    @DisplayName("Should report expiresAt consistent with custom expiry")
    void shouldReportExpiresAtForCustomExpiry() {
        java.time.Instant before = java.time.Instant.now();
        S3PresignedUrlService.PresignedUrlResult result =
                service.generatePresignedUrl("some/key.parquet", "file.parquet", Duration.ofSeconds(60));

        assertFalse(result.expiresAt().isAfter(before.plusSeconds(61)));
        assertTrue(result.expiresAt().isAfter(before.plusSeconds(50)));
    }

    private GetObjectPresignRequest capturedPresignRequest() {
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        org.mockito.Mockito.verify(s3Presigner).presignGetObject(captor.capture());
        return captor.getValue();
    }
}
