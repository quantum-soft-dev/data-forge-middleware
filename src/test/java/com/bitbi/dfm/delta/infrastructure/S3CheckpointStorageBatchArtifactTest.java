package com.bitbi.dfm.delta.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3CheckpointStorageBatchArtifactTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsUnifiedArtifactFromAFileRequestBody() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3CheckpointStorage storage = new S3CheckpointStorage(s3, "bucket");
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        Path file = tempDir.resolve("artifact.parquet");
        byte[] content = new byte[] {1, 2, 3, 4, 5};
        Files.write(file, content);

        String key = storage.uploadBatchParquet(siteId, batchId, "orders/history", claimToken, file);

        assertEquals("egress/%s/batches/%s/attempts/%s/orders%%2Fhistory.parquet"
                .formatted(siteId, batchId, claimToken), key);
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals((long) content.length, request.getValue().contentLength());
        assertEquals("application/vnd.apache.parquet", request.getValue().contentType());
        assertArrayEquals(content, body.getValue().contentStreamProvider().newStream().readAllBytes());
    }
}
