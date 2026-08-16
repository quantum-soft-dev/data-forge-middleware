package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactKey;
import com.bitbi.dfm.shared.storage.S3PrefixLister;
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Stores materialized checkpoint snapshot files (Delta Client v2 — 022).
 *
 * <p>Layout: {@code checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet}. Builds before issue
 * #113 also wrote a {@code snapshot.csv.gz} sibling; those objects are still read (and deleted) by
 * site wipe through the keys recorded on the checkpoint rows, but none are written any more.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class S3CheckpointStorage {

    private static final Logger log = LoggerFactory.getLogger(S3CheckpointStorage.class);

    /** Short enough that readiness is not held hostage to an unreachable S3 at startup. */
    private static final Duration STARTUP_CHECK_TIMEOUT = Duration.ofSeconds(5);

    /** The prefix every checkpoint object of every site lives under. */
    private static final String CHECKPOINT_ROOT_PREFIX = "checkpoints/";

    private final S3Client s3Client;
    private final String bucketName;
    private final Counter readDenied;

    public S3CheckpointStorage(S3Client s3Client, @Value("${s3.bucket.name}") String bucketName,
                               MeterRegistry registry) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        // Registered here rather than in DeltaMetrics: the denial is an S3-level fact observed in
        // this class, and infrastructure must not depend on the application layer. Same shape as
        // CheckpointGivenUpMetrics, which DeltaMetrics documents without owning.
        this.readDenied = Counter.builder("delta.s3.read-denied")
                .description("Objects whose presence could not be determined because S3 denied "
                        + "both the HEAD and the one-key listing probe")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }

    /** What a startup listing of the bucket said about {@code s3:ListBucket}. */
    public enum ListPermission {
        /** The bucket can be listed, so {@link #presence} can resolve a denied HEAD. */
        GRANTED,
        /** Listing is denied: absence and denial are indistinguishable for this principal. */
        DENIED,
        /** S3 could not be reached; nothing may be concluded about the permission. */
        UNDETERMINED
    }

    /**
     * Check at startup that this application may list its bucket (issue #157, raised in review).
     *
     * <p>{@link #presence} resolves a denied HEAD by listing, which only works while
     * {@code s3:ListBucket} is granted. That is not a new requirement — site wipe walks two
     * prefixes (#118, #122) and the nightly batch retention lists the batch prefix (#100), so a
     * deployment without it is already broken — but it was an assumption nothing verified, and the
     * way it fails is quiet: every absent object answers {@code UNKNOWN}, the checkpoint build
     * skips those sites for as long as it lasts, and {@code delta.s3.read-denied} climbs by one per
     * missing key instead of marking an incident. One listing at startup turns that into a line an
     * operator can act on.</p>
     *
     * <p>It does not fail the context: an S3 that is briefly unreachable at startup must not stop a
     * pod from serving, and the permission may equally be fixed while the pod runs. For the same
     * reason the call carries its own short timeout (raised in review): a ready listener runs
     * <em>before</em> Boot flips readiness to {@code ACCEPTING_TRAFFIC}, and the SDK's default
     * 30 s socket timeout across three attempts would keep a pod out of the Service endpoints for a
     * minute and a half — a stalled rollout, which is the opposite of not stopping a pod from
     * serving. It would also delay the sibling ready listener that re-drives orphaned rebuilds.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logListPermission() {
        switch (verifyListPermission()) {
            case GRANTED -> log.info("S3 list permission confirmed for bucket {} — a denied HEAD "
                    + "can be resolved into present/absent", bucketName);
            case DENIED -> log.error("s3:ListBucket is DENIED for bucket {}. This application needs "
                    + "it for site history wipe and batch retention, and without it S3 cannot tell "
                    + "a missing object from a denied one: every absent object reads as unknown, "
                    + "checkpoint builds skip those sites, and delta.s3.read-denied counts one per "
                    + "missing key rather than marking an incident. Grant s3:ListBucket on the "
                    + "bucket", bucketName);
            case UNDETERMINED -> log.warn("Could not verify the S3 list permission for bucket {} at "
                    + "startup; if delta.s3.read-denied climbs steadily, check it by hand", bucketName);
        }
    }

    /**
     * One listing, reduced to what it says about the permission.
     *
     * <p>Listed under {@code checkpoints/} rather than at the bucket root (raised in review): a
     * {@code s3:ListBucket} grant may carry an {@code s3:prefix} condition, and then the root
     * answers 403 while the probe this check exists for works perfectly — a false alarm telling an
     * operator to widen a policy that is already right. The checkpoint prefix is where the answer
     * matters most, since a misread frame is the failure #149 makes durable.</p>
     *
     * @return {@code GRANTED}, {@code DENIED} (403) or {@code UNDETERMINED} (anything else)
     */
    public ListPermission verifyListPermission() {
        try {
            s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName).prefix(CHECKPOINT_ROOT_PREFIX).maxKeys(1)
                    // Bounded on purpose: this runs on the ready thread, before readiness is
                    // published. The SDK default would be 30 s x 3 attempts.
                    .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                            .apiCallTimeout(STARTUP_CHECK_TIMEOUT)
                            .apiCallAttemptTimeout(STARTUP_CHECK_TIMEOUT)
                            .build())
                    .build());
            return ListPermission.GRANTED;
        } catch (S3Exception e) {
            return e.statusCode() == 403 ? ListPermission.DENIED : ListPermission.UNDETERMINED;
        } catch (SdkClientException e) {
            return ListPermission.UNDETERMINED;
        }
    }


    /**
     * What S3 was able to tell us about one key (issue #157).
     *
     * <p>{@code UNKNOWN} exists because "absent" and "you may not look" arrive as the same status
     * code on a least-privilege deployment, and the callers act on absence with very different
     * severity — up to declaring a site's whole checkpoint history gone. A caller that cannot act
     * on an undecidable answer must do nothing rather than assume either side.</p>
     */
    public enum ObjectPresence {
        /** The object is there. */
        PRESENT,
        /** The object is not there — S3 said so, it was not inferred from a refusal. */
        ABSENT,
        /** S3 refused to answer. Nothing may be concluded, in either direction. */
        UNKNOWN
    }

    /**
     * Upload a typed Parquet snapshot for a table checkpoint (full per-table load — B3).
     *
     * <p>Layout: {@code checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet}. The snapshot is
     * streamed from a local file (issue #112) — it is never held in heap as a {@code byte[]}.</p>
     *
     * @return the S3 key written
     */
    public String uploadParquet(UUID siteId, String tableName, long seq, Path file) {
        return putFile(String.format("checkpoints/%s/%s/seq=%d/snapshot.parquet", siteId, tableName, seq),
                "application/vnd.apache.parquet", file, "checkpoint Parquet");
    }

    /**
     * Upload one segment's delta Parquet file for a table (event-driven egress, Task 8).
     *
     * <p>Layout: {@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet} with zero-padded
     * sequences, so a consumer listing the prefix gets files in apply order lexicographically.</p>
     *
     * @return the S3 key written
     */
    public String uploadDelta(UUID siteId, String tableName, long firstSeq, long lastSeq, byte[] content) {
        String s3Key = deltaKey(siteId, tableName, firstSeq, lastSeq);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/vnd.apache.parquet")
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            log.info("Stored delta Parquet: key={}, size={}", s3Key, content.length);
            return s3Key;
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to store delta Parquet: " + s3Key, e);
        }
    }

    /** Upload one completed batch/table artifact directly from a local file. */
    public String uploadBatchParquet(UUID siteId, UUID batchId, String tableName,
                                     UUID claimToken, Path file) {
        return putFile(BatchParquetArtifactKey.attempt(siteId, batchId, tableName, claimToken),
                "application/vnd.apache.parquet", file, "unified batch Parquet");
    }

    /**
     * Best-effort removal of one artifact object. Used when a finished build discovers that the row
     * which would have owned the object is gone: nothing else can name that key afterwards, so the
     * uploader is the only one left who can clean up after itself.
     *
     * @return true when the delete was issued without error
     */
    public boolean deleteBatchParquet(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return true;
        } catch (S3Exception e) {
            log.warn("Could not delete orphaned unified batch Parquet {}", s3Key, e);
            return false;
        }
    }

    /** Stable, unique object key for one logical batch/table artifact. */
    public static String batchParquetKey(UUID siteId, UUID batchId, String tableName) {
        return BatchParquetArtifactKey.of(siteId, batchId, tableName);
    }

    /** Deterministic namespace used to reclaim every attempt belonging to one batch. */
    public static String batchParquetPrefix(UUID siteId, UUID batchId) {
        return BatchParquetArtifactKey.batchPrefix(siteId, batchId);
    }

    /** @return the delta Parquet key for one table's slice of a segment (feature 025). */
    public static String deltaKey(UUID siteId, String tableName, long firstSeq, long lastSeq) {
        return String.format("egress/%s/%s/delta/seq=%019d-%019d.parquet", siteId, tableName, firstSeq, lastSeq);
    }

    /**
     * @return whether the delta Parquet file of one table's segment slice is there, gone, or
     *         undecidable (feature 025; tri-state since issue #157)
     */
    public ObjectPresence deltaPresence(UUID siteId, String tableName, long firstSeq, long lastSeq) {
        return presence(deltaKey(siteId, tableName, firstSeq, lastSeq));
    }

    /** @return the prefix holding every delta Parquet file of a site (feature 025). */
    public static String egressPrefix(UUID siteId) {
        return String.format("egress/%s/", siteId);
    }

    /**
     * @param siteId the site
     * @return the prefix holding every checkpoint object of a site — the per-table snapshots of
     *         every build plus the {@code _frame/} reload frames (issue #118)
     */
    public static String checkpointPrefix(UUID siteId) {
        return CHECKPOINT_ROOT_PREFIX + siteId + "/";
    }

    /** @return the keys of all objects under a prefix (single page is sufficient for test/egress sizes). */
    public List<String> listKeys(String prefix) {
        try {
            return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucketName).prefix(prefix).build())
                    .contents().stream().map(S3Object::key).toList();
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to list objects under prefix: " + prefix, e);
        }
    }

    /**
     * Every object under a prefix, following continuation tokens.
     *
     * <p>Unlike {@link #listKeys}, this is the whole-prefix walk (site history wipe of
     * {@code egress/{siteId}/} and {@code checkpoints/{siteId}/}). A mid-pagination failure
     * returns the pages already read plus {@code truncated=true} instead of throwing the
     * walk away.</p>
     *
     * @param prefix the prefix to enumerate
     * @return the pages read, with lastModified per object and a truncation flag
     */
    public S3PrefixListing listPrefix(String prefix) {
        return S3PrefixLister.listAll(s3Client, bucketName, prefix);
    }

    /**
     * Upload the all-INSERT checkpoint frame (reloadable seed) for a site at a given sequence.
     *
     * <p>Layout: {@code checkpoints/{siteId}/_frame/seq={seq}/frame.pb.gz}. The frame is streamed
     * from a local file (issue #126) — it is never held in heap as a {@code byte[]}.</p>
     *
     * @return the S3 key written
     */
    public String uploadFrame(UUID siteId, long seq, Path file) {
        return putFile(frameKey(siteId, seq), "application/octet-stream", file, "checkpoint frame");
    }

    /**
     * Stream a local file to {@code s3Key}. {@code RequestBody.fromFile} opens the file lazily
     * and wraps a read failure in {@link UncheckedIOException} — convert that too, so every
     * failure of this upload is one {@link CheckpointStorageException}.
     */
    private String putFile(String s3Key, String contentType, Path file, String what) {
        try {
            long size = java.nio.file.Files.size(file);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(size)
                    .build();
            s3Client.putObject(request, RequestBody.fromFile(file));
            log.info("Stored {}: key={}, size={}", what, s3Key, size);
            return s3Key;
        } catch (S3Exception | IOException | UncheckedIOException e) {
            throw new CheckpointStorageException("Failed to store " + what + ": " + s3Key, e);
        }
    }

    /**
     * Open the checkpoint frame of a site at a given sequence for streaming.
     *
     * <p>A stream rather than the {@code byte[]} this used to return (issue #152). The frame is the
     * whole site as an all-INSERT changelog, so materializing it cost a full gzipped copy on the
     * heap before {@code ChangelogCodec} expanded it into a second one — on a pod sized in
     * gigabytes that, not the scratch ceilings, was the first thing a growing site hit. The read
     * side is now the mirror of {@link #uploadFrame}, which has streamed from a file since #126.</p>
     *
     * <p>The caller closes the stream. Closing it early aborts the transfer, which is what an
     * abandoned build wants.</p>
     *
     * @return the object's stream, positioned at the first byte
     */
    public InputStream openFrame(UUID siteId, long seq) {
        return open(frameKey(siteId, seq)).inputStream();
    }

    /**
     * @return whether the checkpoint frame of a site at a given sequence is there, gone, or
     *         undecidable — the distinction the build acts on (issue #157)
     */
    public ObjectPresence framePresence(UUID siteId, long seq) {
        return presence(frameKey(siteId, seq));
    }

    private static String frameKey(UUID siteId, long seq) {
        return String.format("checkpoints/%s/_frame/seq=%d/frame.pb.gz", siteId, seq);
    }

    public byte[] download(String s3Key) {
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucketName).key(s3Key).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new CheckpointStorageException("Checkpoint snapshot not found: " + s3Key, e);
        } catch (S3Exception | IOException e) {
            throw new CheckpointStorageException("Failed to read checkpoint snapshot: " + s3Key, e);
        }
    }

    /**
     * Open a checkpoint snapshot for streaming download, exposing its byte length.
     *
     * @return the object's input stream paired with its content length
     */
    public CheckpointObject open(String s3Key) {
        try {
            ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return new CheckpointObject(in, in.response().contentLength());
        } catch (NoSuchKeyException e) {
            throw new CheckpointStorageException("Checkpoint snapshot not found: " + s3Key, e);
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to read checkpoint snapshot: " + s3Key, e);
        }
    }

    /**
     * Byte length of a checkpoint snapshot (HEAD), without transferring the object.
     */
    public long contentLength(String s3Key) {
        try {
            return s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build()).contentLength();
        } catch (NoSuchKeyException e) {
            throw new CheckpointStorageException("Checkpoint snapshot not found: " + s3Key, e);
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        }
    }

    /**
     * Is this object there, gone, or is S3 refusing to say (issue #157)?
     *
     * <p>HEAD on a missing key answers 404 only with {@code s3:ListBucket}; without it AWS hides
     * existence behind a <b>403</b>, so a 403 must not be read as "unavailable". It must not be
     * read as "absent" either: a blanket read denial on keys that <em>do</em> exist lands in exactly
     * the same branch, and before this the two were the same answer — which is how one IAM change
     * presented as every pruned-history site having lost its checkpoint history at once.</p>
     *
     * <p>So the 403 is resolved by <b>listing</b> the key. Not by reading it: AWS applies the same
     * existence-hiding rule to {@code GetObject} as to {@code HeadObject}, so a ranged read would
     * answer 403 for a missing key on exactly the deployment that needs resolving, and every
     * absence would degrade into {@code UNKNOWN}. {@code ListObjectsV2} is a <em>bucket</em> action
     * with no such rule: granted, it says truthfully whether the key is there. This application
     * requires {@code s3:ListBucket} regardless — site wipe walks two prefixes (#118, #122) and the
     * nightly batch retention lists the batch prefix (#100) — so the probe is decidable wherever
     * this application can run at all, and a 403 that survives it is a genuine read denial.
     * {@code UNKNOWN} is therefore a real read outage rather than a guess about one, and the extra
     * round trip is paid on the 403 path alone, which is the rare one.</p>
     *
     * @param s3Key the object key
     * @return {@code PRESENT}, {@code ABSENT}, or {@code UNKNOWN}
     * @throws CheckpointStorageException if S3 failed in a way that is neither absence nor a denial
     */
    public ObjectPresence presence(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return ObjectPresence.PRESENT;
        } catch (NoSuchKeyException e) {
            return ObjectPresence.ABSENT;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return ObjectPresence.ABSENT;
            }
            if (e.statusCode() == 403) {
                return probeAfterDeniedHead(s3Key);
            }
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        } catch (SdkClientException e) {
            // network failures arrive as raw SdkClientException — wrap so callers see one type
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        }
    }

    /**
     * List the key as its own prefix: one round trip, one result, and an answer that is not subject
     * to the existence-hiding rule the HEAD just ran into.
     *
     * <p>Deliberately total: every outcome maps to a presence rather than to an exception, because
     * the question being answered is already "we could not stat it — can we tell anything at all?"
     * A caller receiving {@code UNKNOWN} skips the work; a caller receiving an exception here would
     * have to treat it identically, with a stack trace instead of a decision.</p>
     */
    private ObjectPresence probeAfterDeniedHead(String s3Key) {
        try {
            // Prefix, so the answer must be checked for the exact key: a longer sibling
            // (…/snapshot.parquet.bak) shares the prefix and is not this object.
            boolean listed = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucketName).prefix(s3Key).maxKeys(1).build())
                    .contents().stream().anyMatch(object -> s3Key.equals(object.key()));
            return listed ? ObjectPresence.PRESENT : ObjectPresence.ABSENT;
        } catch (S3Exception e) {
            if (e.statusCode() == 403) {
                readDenied.increment();
                log.warn("S3 read denied for {} — HEAD and the one-key listing both answered 403, "
                        + "so whether the object exists is unknown (delta.s3.read-denied). Work "
                        + "that depends on this object is being skipped, not written off. Check "
                        + "IAM/bucket-policy read permissions; note that s3:ListBucket is required "
                        + "by this application anyway (site wipe, batch retention), and without it "
                        + "S3 cannot tell absence from denial for anyone", s3Key);
                return ObjectPresence.UNKNOWN;
            }
            return unreadable(s3Key, e);
        } catch (SdkClientException e) {
            return unreadable(s3Key, e);
        }
    }

    /**
     * The probe was neither answered nor refused — S3 is unwell. Not counted as a denial:
     * {@code delta.s3.read-denied} has to mean "denied" for an operator to act on it.
     */
    private static ObjectPresence unreadable(String s3Key, Exception cause) {
        log.warn("Could not determine whether {} exists: HEAD was denied (403) and the one-key "
                + "listing failed", s3Key, cause);
        return ObjectPresence.UNKNOWN;
    }

    /**
     * Yes/no form of {@link #presence(String)} for callers that have no third answer to give —
     * an undecidable presence collapses to {@code false}, never to {@code true}.
     */
    public boolean exists(String s3Key) {
        return presence(s3Key) == ObjectPresence.PRESENT;
    }

    /**
     * A checkpoint snapshot opened for streaming, with its content length.
     */
    public record CheckpointObject(java.io.InputStream inputStream, long size) {
    }

    public static class CheckpointStorageException extends RuntimeException {
        public CheckpointStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
