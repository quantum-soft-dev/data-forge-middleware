package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link S3CheckpointStorage#exists(String)} — absence vs unavailability taxonomy.
 *
 * <p>HEAD on a missing key answers 404 only when the caller has {@code s3:ListBucket};
 * in a least-privilege IAM setup (GetObject/PutObject only) AWS answers <b>403</b> for a
 * missing key. Both must read as "absent" (→ 404 to the user), not as storage being
 * unavailable (→ retryable 503).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3CheckpointStorage.exists()")
class S3CheckpointStorageExistsTest {

    private static final String KEY = "egress/site/orders/delta/seq=1-2.parquet";

    @Mock
    private S3Client s3Client;

    private S3CheckpointStorage storage() {
        return new S3CheckpointStorage(s3Client, "test-bucket");
    }

    private static S3Exception s3Exception(int statusCode) {
        // Cast required: S3Exception.builder() is typed as AwsServiceException.Builder.
        return (S3Exception) S3Exception.builder().statusCode(statusCode).message("boom").build();
    }

    @Test
    @DisplayName("returns true when HEAD succeeds")
    void shouldReturnTrueWhenHeadSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertTrue(storage().exists(KEY));
    }

    @Test
    @DisplayName("returns false on NoSuchKeyException")
    void shouldReturnFalseOnNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertFalse(storage().exists(KEY));
    }

    @Test
    @DisplayName("returns false on a plain S3Exception with status 404")
    void shouldReturnFalseOnPlain404() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(404));

        assertFalse(storage().exists(KEY));
    }

    @Test
    @DisplayName("returns false on 403 (HEAD on a missing key without s3:ListBucket)")
    void shouldReturnFalseOn403() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(403));

        assertFalse(storage().exists(KEY));
    }

    @Test
    @DisplayName("wraps a 5xx S3Exception as CheckpointStorageException (retryable 503)")
    void shouldWrapServerErrors() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(500));

        assertThrows(CheckpointStorageException.class, () -> storage().exists(KEY));
    }

    @Test
    @DisplayName("wraps SdkClientException (network failure) as CheckpointStorageException")
    void shouldWrapNetworkFailures() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertThrows(CheckpointStorageException.class, () -> storage().exists(KEY));
    }
}
