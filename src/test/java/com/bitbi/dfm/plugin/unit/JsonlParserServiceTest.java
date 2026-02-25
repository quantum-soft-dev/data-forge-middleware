package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.JsonlParserService;
import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JsonlParserService.
 */
@DisplayName("JsonlParserService")
@ExtendWith(MockitoExtension.class)
class JsonlParserServiceTest {

    @Mock
    private S3Client s3Client;

    private JsonlParserService service;

    private static final String BUCKET = "test-bucket";

    @BeforeEach
    void setUp() {
        service = new JsonlParserService(s3Client, BUCKET, new ObjectMapper());
    }

    // --- helpers ---

    private void mockS3WithContent(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(bytes)
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    }

    private void mockS3WithGzippedContent(String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(bos.toByteArray())
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    }

    @Nested
    @DisplayName("INSERT records")
    class InsertRecords {

        @Test
        @DisplayName("should parse valid INSERT record with data field")
        void shouldParseValidInsert() throws IOException {
            mockS3WithContent("{\"op\":\"I\",\"d\":{\"id\":1,\"name\":\"Alice\"}}");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(1);
            JsonlChangeRecord r = records.get(0);
            assertThat(r.op()).isEqualTo("I");
            assertThat(r.data()).containsEntry("id", 1);
            assertThat(r.data()).containsEntry("name", "Alice");
            assertThat(r.key()).isNull();
            assertThat(r.lineNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw InvalidJsonlException for INSERT missing 'd' field")
        void shouldThrowForInsertMissingData() {
            mockS3WithContent("{\"op\":\"I\",\"k\":{\"id\":1}}");

            assertThatThrownBy(() -> service.parseFromS3("test/file.jsonl"))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Missing 'd' field for INSERT");
        }
    }

    @Nested
    @DisplayName("UPDATE records")
    class UpdateRecords {

        @Test
        @DisplayName("should parse valid UPDATE record with key and data")
        void shouldParseValidUpdate() throws IOException {
            mockS3WithContent("{\"op\":\"U\",\"k\":{\"id\":1},\"d\":{\"name\":\"Bob\"}}");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(1);
            JsonlChangeRecord r = records.get(0);
            assertThat(r.op()).isEqualTo("U");
            assertThat(r.key()).containsEntry("id", 1);
            assertThat(r.data()).containsEntry("name", "Bob");
        }

        @Test
        @DisplayName("should parse UPDATE record with key only (no data)")
        void shouldParseUpdateWithoutData() throws IOException {
            mockS3WithContent("{\"op\":\"U\",\"k\":{\"id\":1}}");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).containsEntry("id", 1);
            assertThat(records.get(0).data()).isNull();
        }

        @Test
        @DisplayName("should throw InvalidJsonlException for UPDATE missing 'k' field")
        void shouldThrowForUpdateMissingKey() {
            mockS3WithContent("{\"op\":\"U\",\"d\":{\"name\":\"Alice\"}}");

            assertThatThrownBy(() -> service.parseFromS3("test/file.jsonl"))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Missing 'k' field for UPDATE");
        }
    }

    @Nested
    @DisplayName("DELETE records")
    class DeleteRecords {

        @Test
        @DisplayName("should parse valid DELETE record with key only")
        void shouldParseValidDelete() throws IOException {
            mockS3WithContent("{\"op\":\"D\",\"k\":{\"id\":99}}");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(1);
            JsonlChangeRecord r = records.get(0);
            assertThat(r.op()).isEqualTo("D");
            assertThat(r.key()).containsEntry("id", 99);
            assertThat(r.data()).isNull();
        }

        @Test
        @DisplayName("should throw InvalidJsonlException for DELETE missing 'k' field")
        void shouldThrowForDeleteMissingKey() {
            mockS3WithContent("{\"op\":\"D\"}");

            assertThatThrownBy(() -> service.parseFromS3("test/file.jsonl"))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Missing 'k' field for DELETE");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw InvalidJsonlException for missing 'op' field")
        void shouldThrowForMissingOp() {
            mockS3WithContent("{\"d\":{\"id\":1}}");

            assertThatThrownBy(() -> service.parseFromS3("test/file.jsonl"))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Missing or non-string 'op'");
        }

        @Test
        @DisplayName("should throw InvalidJsonlException for unknown op value")
        void shouldThrowForUnknownOp() {
            mockS3WithContent("{\"op\":\"X\",\"d\":{\"id\":1}}");

            assertThatThrownBy(() -> service.parseFromS3("test/file.jsonl"))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Unknown op 'X'");
        }

        @Test
        @DisplayName("should skip malformed JSON lines and continue parsing")
        void shouldSkipMalformedJsonAndContinue() throws IOException {
            String content = "not-valid-json\n{\"op\":\"I\",\"d\":{\"id\":2}}\n";
            mockS3WithContent(content);

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(1);
            assertThat(records.get(0).op()).isEqualTo("I");
        }

        @Test
        @DisplayName("should skip empty lines")
        void shouldSkipEmptyLines() throws IOException {
            String content = "\n{\"op\":\"I\",\"d\":{\"id\":1}}\n\n{\"op\":\"D\",\"k\":{\"id\":2}}\n";
            mockS3WithContent(content);

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list for empty file")
        void shouldReturnEmptyListForEmptyFile() throws IOException {
            mockS3WithContent("");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).isEmpty();
        }
    }

    @Nested
    @DisplayName("Multiple records")
    class MultipleRecords {

        @Test
        @DisplayName("should parse multiple records in order")
        void shouldParseMultipleRecordsInOrder() throws IOException {
            String content = "{\"op\":\"I\",\"d\":{\"id\":1}}\n" +
                             "{\"op\":\"U\",\"k\":{\"id\":1},\"d\":{\"name\":\"Updated\"}}\n" +
                             "{\"op\":\"D\",\"k\":{\"id\":1}}\n";
            mockS3WithContent(content);

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(3);
            assertThat(records.get(0).op()).isEqualTo("I");
            assertThat(records.get(1).op()).isEqualTo("U");
            assertThat(records.get(2).op()).isEqualTo("D");
        }

        @Test
        @DisplayName("should track line numbers correctly")
        void shouldTrackLineNumbersCorrectly() throws IOException {
            String content = "{\"op\":\"I\",\"d\":{\"id\":1}}\n" +
                             "\n" +  // empty line — skipped
                             "{\"op\":\"D\",\"k\":{\"id\":1}}\n";
            mockS3WithContent(content);

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl");

            assertThat(records).hasSize(2);
            assertThat(records.get(0).lineNumber()).isEqualTo(1);
            assertThat(records.get(1).lineNumber()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("GZIP support")
    class GzipSupport {

        @Test
        @DisplayName("should parse gzipped JSONL file")
        void shouldParseGzippedFile() throws IOException {
            mockS3WithGzippedContent("{\"op\":\"I\",\"d\":{\"id\":1,\"val\":\"test\"}}");

            List<JsonlChangeRecord> records = service.parseFromS3("test/file.jsonl.gz");

            assertThat(records).hasSize(1);
            assertThat(records.get(0).op()).isEqualTo("I");
            assertThat(records.get(0).data()).containsEntry("val", "test");
        }
    }
}
