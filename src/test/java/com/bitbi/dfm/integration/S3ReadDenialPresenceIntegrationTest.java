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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.DelegatingS3Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #157 — a read denial on an object that is <b>there</b> must not read as that object being
 * gone, and a key that is genuinely gone must still read as absent under least-privilege IAM.
 *
 * <p>The denial is injected at the HEAD call and only there: everything the decision actually rests
 * on — the ranged {@code GetObject} against a real object, and S3's own answer for a key that does
 * not exist — runs against LocalStack. That split is deliberate rather than convenient. LocalStack
 * community does not enforce IAM policies or bucket policies, so a genuine 403 on an existing key
 * cannot be produced there at all; what can be produced, and is the half a unit test cannot prove,
 * is that a ranged read of the first byte succeeds on a real object and that a missing key really
 * does answer {@code NoSuchKey} rather than something the probe would have to guess about.</p>
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

    /** How S3 answers the ranged GET probe while the deny is in force. */
    private enum GetPolicy { ALLOW, DENY }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private S3CheckpointStorage storageWith(HeadPolicy head, GetPolicy get) {
        return new S3CheckpointStorage(new DeniedReadsS3Client(realS3Client, head, get), bucket, registry);
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
    @DisplayName("an existing frame stays PRESENT when HEAD is denied — the probe reads it")
    void shouldSeeAnExistingObjectThroughADeniedHead() {
        long seq = 41L;
        writeFrame(seq);

        assertEquals(ObjectPresence.PRESENT,
                storageWith(HeadPolicy.DENY, GetPolicy.ALLOW).framePresence(SITE, seq));
        assertEquals(0.0, readDenied(), "a resolvable denial is not a read outage");
    }

    @Test
    @DisplayName("a missing frame is still ABSENT when HEAD is denied — S3 itself says NoSuchKey")
    void shouldStillReadAMissingObjectAsAbsent() {
        // The least-privilege reading this ticket must not break: with GetObject/PutObject only,
        // HEAD on a missing key answers 403, and that key really is not there.
        assertEquals(ObjectPresence.ABSENT,
                storageWith(HeadPolicy.DENY, GetPolicy.ALLOW).framePresence(SITE, 4242L));
        assertEquals(0.0, readDenied());
    }

    @Test
    @DisplayName("an existing frame is UNKNOWN, and counted, when the read itself is denied")
    void shouldRefuseToConcludeWhenEveryReadIsDenied() {
        long seq = 43L;
        writeFrame(seq);

        assertEquals(ObjectPresence.UNKNOWN,
                storageWith(HeadPolicy.DENY, GetPolicy.DENY).framePresence(SITE, seq));
        assertEquals(1.0, readDenied());
    }

    @Test
    @DisplayName("nothing changes for a deployment that is allowed to HEAD")
    void shouldNotProbeWhenHeadIsAllowed() {
        long seq = 44L;
        writeFrame(seq);

        S3CheckpointStorage storage = storageWith(HeadPolicy.ALLOW, GetPolicy.DENY);

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
        private final GetPolicy getPolicy;

        private DeniedReadsS3Client(S3Client delegate, HeadPolicy headPolicy, GetPolicy getPolicy) {
            super(delegate);
            this.headPolicy = headPolicy;
            this.getPolicy = getPolicy;
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest request) {
            if (headPolicy == HeadPolicy.DENY) {
                throw denied();
            }
            return super.headObject(request);
        }

        @Override
        public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
            if (getPolicy == GetPolicy.DENY) {
                throw denied();
            }
            return super.getObject(request);
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
