package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3CheckpointStorage#presence(String)} — present vs absent vs unknown (issue #157).
 *
 * <p>HEAD on a missing key answers 404 only when the caller has {@code s3:ListBucket}; in a
 * least-privilege IAM setup (GetObject/PutObject only) AWS answers <b>403</b> for a missing key.
 * That reading is kept — but a blanket read denial on keys that <em>do</em> exist lands in the same
 * branch, so a 403 is resolved with the permission we do have: a ranged {@code GetObject} answers
 * {@code NoSuchKey} for a key that is genuinely gone and succeeds for a denied-but-present one.
 * Only when the probe is denied too is the answer {@code UNKNOWN}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3CheckpointStorage.presence()")
class S3CheckpointStoragePresenceTest {

    private static final String KEY = "egress/site/orders/delta/seq=1-2.parquet";

    @Mock
    private S3Client s3Client;

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private S3CheckpointStorage storage() {
        return new S3CheckpointStorage(s3Client, "test-bucket", registry);
    }

    private double readDenied() {
        return registry.get("delta.s3.read-denied").counter().count();
    }

    private static S3Exception s3Exception(int statusCode) {
        // Cast required: S3Exception.builder() is typed as AwsServiceException.Builder.
        return (S3Exception) S3Exception.builder().statusCode(statusCode).message("boom").build();
    }

    private void headDenied() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(403));
    }

    private void probeReturnsBytes() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(1L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[] {7}))));
    }

    @Test
    @DisplayName("PRESENT when HEAD succeeds, with no probe")
    void shouldBePresentWhenHeadSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertEquals(ObjectPresence.PRESENT, storage().presence(KEY));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("ABSENT on NoSuchKeyException, with no probe")
    void shouldBeAbsentOnNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("ABSENT on a plain S3Exception with status 404, with no probe")
    void shouldBeAbsentOnPlain404() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(404));

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("ABSENT when HEAD is denied but the ranged probe answers NoSuchKey")
    void shouldBeAbsentWhenProbeSaysNoSuchKey() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("ABSENT when HEAD is denied but the ranged probe answers 404")
    void shouldBeAbsentWhenProbeSaysPlain404() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception(404));

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
    }

    @Test
    @DisplayName("PRESENT when HEAD is denied but the ranged probe reads the first byte")
    void shouldBePresentWhenProbeReadsTheObject() {
        headDenied();
        probeReturnsBytes();

        assertEquals(ObjectPresence.PRESENT, storage().presence(KEY));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("probes the first byte only, so a denied HEAD costs one byte and not the object")
    void shouldProbeWithAOneByteRange() {
        headDenied();
        probeReturnsBytes();

        storage().presence(KEY);

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(request.capture());
        assertEquals("bytes=0-0", request.getValue().range());
        assertEquals(KEY, request.getValue().key());
        assertEquals("test-bucket", request.getValue().bucket());
    }

    @Test
    @DisplayName("PRESENT when the probe answers 416: a zero-length object has no first byte")
    void shouldBePresentOnInvalidRange() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception(416));

        assertEquals(ObjectPresence.PRESENT, storage().presence(KEY));
    }

    @Test
    @DisplayName("UNKNOWN, and counted, when the probe is denied too")
    void shouldBeUnknownWhenTheProbeIsDeniedAsWell() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception(403));

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
        assertEquals(1.0, readDenied());
    }

    @Test
    @DisplayName("UNKNOWN, uncounted, when the probe fails for a reason other than a denial")
    void shouldBeUnknownButUncountedWhenTheProbeFails() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception(500));

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
        assertEquals(0.0, readDenied(), "delta.s3.read-denied means denied, not unreachable");
    }

    @Test
    @DisplayName("UNKNOWN when the probe cannot reach S3 at all")
    void shouldBeUnknownWhenTheProbeCannotReachS3() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
    }

    @Test
    @DisplayName("the counter is registered at zero before the first denial")
    void shouldRegisterTheCounterAtStartup() {
        storage();

        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("wraps a 5xx S3Exception as CheckpointStorageException (retryable 503)")
    void shouldWrapServerErrors() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(500));

        assertThrows(CheckpointStorageException.class, () -> storage().presence(KEY));
    }

    @Test
    @DisplayName("wraps SdkClientException (network failure) as CheckpointStorageException")
    void shouldWrapNetworkFailures() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertThrows(CheckpointStorageException.class, () -> storage().presence(KEY));
    }

    @Test
    @DisplayName("exists() keeps its yes/no contract: an undecidable answer is not a yes")
    void shouldCollapseUnknownToFalseForExists() {
        headDenied();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception(403));

        assertFalse(storage().exists(KEY));
    }

    @Test
    @DisplayName("exists() is true when the object is there")
    void shouldReturnTrueWhenHeadSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertTrue(storage().exists(KEY));
    }

    @Test
    @DisplayName("framePresence and deltaPresence answer for their own keys")
    void shouldAnswerForFrameAndDeltaKeys() {
        UUID siteId = UUID.randomUUID();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertEquals(ObjectPresence.PRESENT, storage().framePresence(siteId, 7L));
        assertEquals(ObjectPresence.PRESENT, storage().deltaPresence(siteId, "orders", 1L, 2L));

        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.times(2)).headObject(request.capture());
        assertEquals("checkpoints/%s/_frame/seq=7/frame.pb.gz".formatted(siteId),
                request.getAllValues().get(0).key());
        assertEquals(S3CheckpointStorage.deltaKey(siteId, "orders", 1L, 2L),
                request.getAllValues().get(1).key());
    }
}
