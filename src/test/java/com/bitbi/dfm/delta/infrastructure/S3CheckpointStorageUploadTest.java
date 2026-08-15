package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link S3CheckpointStorage#uploadParquet} — the checkpoint snapshot is streamed from a local file
 * (issue #112), and every failure of that upload must leave as one {@link CheckpointStorageException}.
 *
 * <p>The file is opened lazily by the SDK's {@code RequestBody.fromFile}, which wraps a read failure
 * in an {@link UncheckedIOException}. Converting it keeps the storage layer's contract one type
 * wide, instead of leaking an SDK internal past a caller that catches
 * {@link S3Exception}-shaped failures.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3CheckpointStorage.uploadParquet()")
class S3CheckpointStorageUploadTest {

    private static final UUID SITE = UUID.randomUUID();

    @Mock
    private S3Client s3Client;

    @TempDir
    Path tempDir;

    @Test
    void streamsTheFileToTheCheckpointKey() throws IOException {
        Path snapshot = Files.writeString(tempDir.resolve("snapshot.parquet"), "parquet-bytes");

        String key = new S3CheckpointStorage(s3Client, "test-bucket")
                .uploadParquet(SITE, "customers", 7L, snapshot);

        assertEquals("checkpoints/" + SITE + "/customers/seq=7/snapshot.parquet", key);
    }

    @Test
    void wrapsAnUncheckedIoFailureOfTheUploadLikeAnyOtherStorageFailure() throws IOException {
        Path snapshot = Files.writeString(tempDir.resolve("snapshot.parquet"), "parquet-bytes");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new UncheckedIOException(new IOException("cannot read the snapshot")));

        assertThrows(CheckpointStorageException.class, () -> new S3CheckpointStorage(s3Client, "test-bucket")
                .uploadParquet(SITE, "customers", 7L, snapshot));
    }
}
