package com.bitbi.dfm.delta.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
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

    public boolean exists(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new CheckpointStorageException("Failed to stat checkpoint snapshot: " + s3Key, e);
        }
    }

    public static class CheckpointStorageException extends RuntimeException {
        public CheckpointStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
