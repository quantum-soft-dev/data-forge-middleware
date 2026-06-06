package com.bitbi.dfm.delta.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

/**
 * Stores raw changelog segment bytes (bronze) in object storage (Delta Client v2 — 022).
 *
 * <p>Layout: {@code delta/{siteId}/segments/{batchId}.pb.gz} (gzipped length-delimited protobuf).</p>
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
     * @param siteId  site identifier
     * @param batchId batch (session) identifier
     * @param content gzipped length-delimited protobuf bytes
     * @return the S3 key the segment was stored at
     */
    public String uploadSegment(UUID siteId, UUID batchId, byte[] content) {
        String s3Key = String.format("delta/%s/segments/%s.pb.gz", siteId, batchId);
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
