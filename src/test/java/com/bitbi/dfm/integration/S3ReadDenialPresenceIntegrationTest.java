package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.DelegatingS3Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #157 — a read denial on an object that is <b>there</b> must not read as that object being
 * gone, while a key that is genuinely gone must still read as absent.
 *
 * <p>The denial is injected at the HEAD call and only there: what the decision actually rests on —
 * the one-key {@code ListObjectsV2} probe — runs against LocalStack, against a real object and
 * against a key that was never written. That split is deliberate rather than convenient. LocalStack
 * community enforces neither IAM nor bucket policies, so a genuine 403 on an existing key cannot be
 * produced there at all; what can be produced, and is the half a unit test cannot prove, is that
 * the probe really does distinguish a written object from a missing one through a real S3 listing —
 * including that it answers on the exact key and not merely on the prefix.</p>
 */
@DisplayName("S3 read denial vs absence (#157)")
class S3ReadDenialPresenceIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SITE = UUID.randomUUID();

    @Autowired
    private S3Client realS3Client;

    @Value("${s3.bucket.name}")
    private String bucket;

    private MeterRegistry registry;

    /** How S3 answers a HEAD while the deny is in force. */
    private enum HeadPolicy { ALLOW, DENY }

    /** How S3 answers the one-key listing probe while the deny is in force. */
    private enum ListPolicy { ALLOW, DENY }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private S3CheckpointStorage storageWith(HeadPolicy head, ListPolicy list) {
        return new S3CheckpointStorage(new DeniedReadsS3Client(realS3Client, head, list), bucket, registry);
    }

    private double readDenied() {
        return registry.get("delta.s3.read-denied").counter().count();
    }

    private String writeFrame(long seq) {
        String key = "checkpoints/%s/_frame/seq=%d/frame.pb.gz".formatted(SITE, seq);
        realS3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromString("not really gzip, but really an object", StandardCharsets.UTF_8));
        return key;
    }

    @Test
    @DisplayName("an existing frame stays PRESENT when HEAD is denied — the probe lists it")
    void shouldSeeAnExistingObjectThroughADeniedHead() {
        long seq = 41L;
        writeFrame(seq);

        assertEquals(ObjectPresence.PRESENT,
                storageWith(HeadPolicy.DENY, ListPolicy.ALLOW).framePresence(SITE, seq));
        assertEquals(0.0, readDenied(), "a resolvable denial is not a read outage");
    }

    @Test
    @DisplayName("a sibling sharing the prefix does not make a missing frame look present")
    void shouldNotMistakeAPrefixSiblingForTheObject() {
        // The probe lists by prefix, so an object whose key merely starts with the frame key —
        // which is what every per-seq sibling under the same directory looks like — must not
        // answer for it.
        long seq = 45L;
        String key = "checkpoints/%s/_frame/seq=%d/frame.pb.gz".formatted(SITE, seq);
        realS3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key + ".partial").build(),
                RequestBody.fromString("a neighbour", StandardCharsets.UTF_8));

        assertEquals(ObjectPresence.ABSENT,
                storageWith(HeadPolicy.DENY, ListPolicy.ALLOW).framePresence(SITE, seq));
    }

    @Test
    @DisplayName("a missing frame is still ABSENT when HEAD is denied — the listing is empty")
    void shouldStillReadAMissingObjectAsAbsent() {
        // The least-privilege reading this ticket must not break: without s3:ListBucket, HEAD on a
        // missing key answers 403, and that key really is not there. The probe says so.
        assertEquals(ObjectPresence.ABSENT,
                storageWith(HeadPolicy.DENY, ListPolicy.ALLOW).framePresence(SITE, 4242L));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("an existing frame is UNKNOWN, and counted, when the read itself is denied")
    void shouldRefuseToConcludeWhenEveryReadIsDenied() {
        long seq = 43L;
        writeFrame(seq);

        assertEquals(ObjectPresence.UNKNOWN,
                storageWith(HeadPolicy.DENY, ListPolicy.DENY).framePresence(SITE, seq));
        assertEquals(1.0, readDenied());
    }

    @Test
    @DisplayName("nothing changes for a deployment that is allowed to HEAD")
    void shouldNotProbeWhenHeadIsAllowed() {
        long seq = 44L;
        writeFrame(seq);

        S3CheckpointStorage storage = storageWith(HeadPolicy.ALLOW, ListPolicy.DENY);

        assertEquals(ObjectPresence.PRESENT, storage.framePresence(SITE, seq));
        assertEquals(ObjectPresence.ABSENT, storage.framePresence(SITE, 4343L));
        assertEquals(0.0, readDenied());
    }

    /**
     * A real S3 client with a policy in front of it: the two read calls can be refused exactly as
     * an IAM or bucket-policy deny refuses them, and every other call — the {@code PutObject} that
     * creates the fixture, and the probe when it is allowed — goes to LocalStack untouched.
     */
    private static final class DeniedReadsS3Client extends DelegatingS3Client {

        private final HeadPolicy headPolicy;
        private final ListPolicy listPolicy;

        private DeniedReadsS3Client(S3Client delegate, HeadPolicy headPolicy, ListPolicy listPolicy) {
            super(delegate);
            this.headPolicy = headPolicy;
            this.listPolicy = listPolicy;
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest request) {
            if (headPolicy == HeadPolicy.DENY) {
                throw denied();
            }
            return super.headObject(request);
        }

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            if (listPolicy == ListPolicy.DENY) {
                throw denied();
            }
            return super.listObjectsV2(request);
        }

        private static S3Exception denied() {
            // Shaped like the real thing: S3 answers an explicit deny with 403 AccessDenied, and
            // says nothing at all about whether the key is there.
            return (S3Exception) S3Exception.builder()
                    .statusCode(403)
                    .message("Access Denied")
                    .build();
        }
    }
}
