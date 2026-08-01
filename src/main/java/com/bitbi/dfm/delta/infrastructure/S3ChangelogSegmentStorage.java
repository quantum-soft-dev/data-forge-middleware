package com.bitbi.dfm.delta.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.UUID;

/**
 * Stores raw changelog segment bytes (bronze) in object storage (Delta Client v2 — 022).
 *
 * <p>Layout: {@code delta/{siteId}/segments/{segmentId}.pb.gz} (gzipped length-delimited protobuf).
 * Keyed by the segment's own id — a session's batch owns many segments (029), so a batch-derived
 * key would collide. Rows written before 029 keep their stored batch-derived keys.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class S3ChangelogSegmentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ChangelogSegmentStorage.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3ChangelogSegmentStorage(S3Client s3Client, @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Upload a changelog segment.
     *
     * @param siteId    site identifier
     * @param segmentId segment identifier (matches the changelog_segments row)
     * @param content   gzipped length-delimited protobuf bytes
     * @return the S3 key the segment was stored at
     */
    public String uploadSegment(UUID siteId, UUID segmentId, byte[] content) {
        String s3Key = String.format("delta/%s/segments/%s.pb.gz", siteId, segmentId);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/octet-stream")
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            log.info("Stored changelog segment: key={}, size={}", s3Key, content.length);
            return s3Key;
        } catch (S3Exception e) {
            throw new SegmentStorageException("Failed to store changelog segment: " + s3Key, e);
        }
    }

    /**
     * Download the raw segment bytes.
     *
     * @param s3Key the segment key
     * @return gzipped length-delimited protobuf bytes
     */
    public byte[] download(String s3Key) {
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucketName).key(s3Key).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new SegmentStorageException("Changelog segment not found: " + s3Key, e);
        } catch (S3Exception | IOException e) {
            throw new SegmentStorageException("Failed to read changelog segment: " + s3Key, e);
        }
    }

    /** Open a raw segment for bounded streaming; the caller must close the returned stream. */
    public ResponseInputStream<GetObjectResponse> open(String s3Key) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(s3Key).build());
        } catch (NoSuchKeyException e) {
            throw new SegmentStorageException("Changelog segment not found: " + s3Key, e);
        } catch (S3Exception e) {
            throw new SegmentStorageException("Failed to open changelog segment: " + s3Key, e);
        }
    }

    /**
     * Delete a segment object (retention). A missing object is treated as already deleted.
     *
     * @param s3Key the segment key
     */
    public void delete(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            log.info("Deleted changelog segment: key={}", s3Key);
        } catch (NoSuchKeyException e) {
            log.debug("Changelog segment already absent: key={}", s3Key);
        } catch (S3Exception e) {
            throw new SegmentStorageException("Failed to delete changelog segment: " + s3Key, e);
        }
    }

    /**
     * @return whether an object exists at the given key
     */
    public boolean exists(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new SegmentStorageException("Failed to stat changelog segment: " + s3Key, e);
        }
    }

    /**
     * Thrown when a segment storage operation fails.
     */
    public static class SegmentStorageException extends RuntimeException {
        public SegmentStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
