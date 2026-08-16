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
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

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
 * <p>HEAD answers 404 for a missing key only when the caller has {@code s3:ListBucket}; without it
 * AWS hides existence behind a <b>403</b>, and it applies that rule to {@code GetObject} just as it
 * does to {@code HeadObject} — which is why the probe here is a one-key {@code ListObjectsV2} and
 * not the ranged read the ticket suggested. This application requires {@code s3:ListBucket} anyway
 * (site wipe and the nightly batch retention both walk prefixes), so the probe is decidable on any
 * deployment it can run on, and a 403 that survives it is a genuine read denial rather than a key
 * that was never there.</p>
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

    private void probeLists(String... keys) {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder()
                        .contents(java.util.Arrays.stream(keys)
                                .map(key -> S3Object.builder().key(key).build())
                                .toList())
                        .build());
    }

    private void probeDenied() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(s3Exception(403));
    }

    @Test
    @DisplayName("PRESENT when HEAD succeeds, with no probe")
    void shouldBePresentWhenHeadSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertEquals(ObjectPresence.PRESENT, storage().presence(KEY));
        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("ABSENT on NoSuchKeyException, with no probe")
    void shouldBeAbsentOnNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    @DisplayName("ABSENT on a plain S3Exception with status 404, with no probe")
    void shouldBeAbsentOnPlain404() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Exception(404));

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    @DisplayName("ABSENT when HEAD is denied but the listing does not contain the key")
    void shouldBeAbsentWhenTheListingIsEmpty() {
        headDenied();
        probeLists();

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
        assertEquals(0.0, readDenied(), "a resolvable denial is not a read outage");
    }

    @Test
    @DisplayName("ABSENT when the listing only returns a longer key sharing the prefix")
    void shouldBeAbsentWhenOnlyAPrefixSiblingIsListed() {
        // ListObjectsV2 matches by prefix, so a key that merely starts with ours is not ours.
        headDenied();
        probeLists(KEY + ".bak");

        assertEquals(ObjectPresence.ABSENT, storage().presence(KEY));
    }

    @Test
    @DisplayName("PRESENT when HEAD is denied but the listing contains the key")
    void shouldBePresentWhenTheListingContainsTheKey() {
        headDenied();
        probeLists(KEY);

        assertEquals(ObjectPresence.PRESENT, storage().presence(KEY));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("probes with the key as an exact prefix and one result")
    void shouldProbeWithAOneKeyListing() {
        headDenied();
        probeLists(KEY);

        storage().presence(KEY);

        ArgumentCaptor<ListObjectsV2Request> request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client).listObjectsV2(request.capture());
        assertEquals(KEY, request.getValue().prefix());
        assertEquals(1, request.getValue().maxKeys());
        assertEquals("test-bucket", request.getValue().bucket());
    }

    @Test
    @DisplayName("UNKNOWN, and counted, when the listing is denied too")
    void shouldBeUnknownWhenTheProbeIsDeniedAsWell() {
        headDenied();
        probeDenied();

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
        assertEquals(1.0, readDenied());
    }

    @Test
    @DisplayName("UNKNOWN, uncounted, when the listing fails for a reason other than a denial")
    void shouldBeUnknownButUncountedWhenTheProbeFails() {
        headDenied();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(s3Exception(500));

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
        assertEquals(0.0, readDenied(), "delta.s3.read-denied means denied, not unreachable");
    }

    @Test
    @DisplayName("UNKNOWN when the probe cannot reach S3 at all")
    void shouldBeUnknownWhenTheProbeCannotReachS3() {
        headDenied();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertEquals(ObjectPresence.UNKNOWN, storage().presence(KEY));
    }

    @Test
    @DisplayName("the startup check confirms the list permission the probe depends on")
    void shouldConfirmTheListPermissionAtStartup() {
        probeLists();

        assertEquals(S3CheckpointStorage.ListPermission.GRANTED, storage().verifyListPermission());
    }

    @Test
    @DisplayName("the startup check reports a denied list permission — the premise, checked")
    void shouldReportADeniedListPermissionAtStartup() {
        // Without s3:ListBucket the probe cannot decide anything: S3 hides existence behind the
        // same 403 for HEAD and for GET alike, so every absent object would answer UNKNOWN. That
        // deployment cannot run site wipe or batch retention either, and the operator should learn
        // it at startup rather than from a counter that never stops climbing.
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(s3Exception(403));

        assertEquals(S3CheckpointStorage.ListPermission.DENIED, storage().verifyListPermission());
        assertEquals(0.0, readDenied(), "a startup check is not an object whose presence we wanted");
    }

    @Test
    @DisplayName("the startup check concludes nothing when S3 is merely unreachable")
    void shouldNotClaimADenialWhenS3IsUnreachable() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertEquals(S3CheckpointStorage.ListPermission.UNDETERMINED, storage().verifyListPermission());
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
        probeDenied();

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
