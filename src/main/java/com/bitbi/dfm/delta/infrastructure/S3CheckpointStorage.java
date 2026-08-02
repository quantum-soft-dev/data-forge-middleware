package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Stores materialized checkpoint snapshot files (Delta Client v2 — 022).
 *
 * <p>Layout: {@code checkpoints/{siteId}/{table}/seq={seq}/snapshot.csv.gz}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class S3CheckpointStorage {

    private static final Logger log = LoggerFactory.getLogger(S3CheckpointStorage.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3CheckpointStorage(S3Client s3Client, @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Upload a gzipped CSV snapshot for a table checkpoint.
     *
     * @return the S3 key written
     */
    public String uploadCsv(UUID siteId, String tableName, long seq, byte[] content) {
        String s3Key = String.format("checkpoints/%s/%s/seq=%d/snapshot.csv.gz", siteId, tableName, seq);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/gzip")
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            log.info("Stored checkpoint CSV: key={}, size={}", s3Key, content.length);
            return s3Key;
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to store checkpoint CSV: " + s3Key, e);
        }
    }

    /**
     * Upload a typed Parquet snapshot for a table checkpoint (full per-table load — B3).
     *
     * <p>Layout: {@code checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet}.</p>
     *
     * @return the S3 key written
     */
    public String uploadParquet(UUID siteId, String tableName, long seq, byte[] content) {
        String s3Key = String.format("checkpoints/%s/%s/seq=%d/snapshot.parquet", siteId, tableName, seq);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/vnd.apache.parquet")
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            log.info("Stored checkpoint Parquet: key={}, size={}", s3Key, content.length);
            return s3Key;
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to store checkpoint Parquet: " + s3Key, e);
        }
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
        String s3Key = BatchParquetArtifactKey.attempt(siteId, batchId, tableName, claimToken);
        try {
            long size = java.nio.file.Files.size(file);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/vnd.apache.parquet")
                    .contentLength(size)
                    .build();
            s3Client.putObject(request, RequestBody.fromFile(file));
            log.info("Stored unified batch Parquet: key={}, size={}", s3Key, size);
            return s3Key;
        } catch (S3Exception | IOException e) {
            throw new CheckpointStorageException("Failed to store unified batch Parquet: " + s3Key, e);
        }
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

    /** @return whether the delta Parquet file of one table's segment slice exists (feature 025). */
    public boolean deltaExists(UUID siteId, String tableName, long firstSeq, long lastSeq) {
        return exists(deltaKey(siteId, tableName, firstSeq, lastSeq));
    }

    /** @return the prefix holding every delta Parquet file of a site (feature 025). */
    public static String egressPrefix(UUID siteId) {
        return String.format("egress/%s/", siteId);
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
     * Every key under a prefix, following continuation tokens.
     *
     * <p>Unlike {@link #listKeys}, this one is meant for whole-site prefixes (issue #89 — the
     * history wipe walks {@code egress/{siteId}/}), where a single 1000-key page truncates
     * silently and would leave most of the objects behind.</p>
     *
     * @param prefix the prefix to enumerate
     * @return all keys under it
     */
    public List<String> listAllKeys(String prefix) {
        try {
            return s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(bucketName).prefix(prefix).build())
                    .contents().stream().map(S3Object::key).toList();
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to list objects under prefix: " + prefix, e);
        }
    }

    /**
     * Upload the all-INSERT checkpoint frame (reloadable seed) for a site at a given sequence.
     *
     * <p>Layout: {@code checkpoints/{siteId}/_frame/seq={seq}/frame.pb.gz}.</p>
     *
     * @return the S3 key written
     */
    public String uploadFrame(UUID siteId, long seq, byte[] content) {
        String s3Key = frameKey(siteId, seq);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/octet-stream")
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            log.info("Stored checkpoint frame: key={}, size={}", s3Key, content.length);
            return s3Key;
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to store checkpoint frame: " + s3Key, e);
        }
    }

    /** Download the checkpoint frame bytes for a site at a given sequence. */
    public byte[] downloadFrame(UUID siteId, long seq) {
        return download(frameKey(siteId, seq));
    }

    /** @return whether a checkpoint frame exists for a site at a given sequence. */
    public boolean frameExists(UUID siteId, long seq) {
        return exists(frameKey(siteId, seq));
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

    public boolean exists(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HEAD on a missing key answers 404 only with s3:ListBucket; least-privilege
            // IAM (Get/PutObject only) answers 403 — both mean "absent", not "unavailable".
            // A blanket deny on EXISTING keys also lands here, so log the 403 branch: it is
            // the only trace distinguishing an IAM outage from genuinely missing files.
            if (e.statusCode() == 404 || e.statusCode() == 403) {
                if (e.statusCode() == 403) {
                    log.warn("S3 HEAD denied (403) for {} — treating as absent; if this repeats "
                            + "for existing keys, check IAM/bucket-policy read permissions", s3Key);
                }
                return false;
            }
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        } catch (SdkClientException e) {
            // network failures arrive as raw SdkClientException — wrap so callers see one type
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        }
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
