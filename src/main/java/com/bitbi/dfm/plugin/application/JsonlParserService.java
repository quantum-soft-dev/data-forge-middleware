package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Parses JSONL delta files (including {@code .jsonl.gz}) from S3 into
 * {@link JsonlChangeRecord} instances.
 *
 * <p>Error handling per specification:</p>
 * <ul>
 *   <li>Malformed JSON line → log warning, skip line and continue</li>
 *   <li>Missing {@code k} field on U or D record → throw {@link InvalidJsonlException} (fatal)</li>
 *   <li>Missing {@code d} field on I record → throw {@link InvalidJsonlException} (fatal)</li>
 *   <li>Unknown op value → throw {@link InvalidJsonlException} (fatal)</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class JsonlParserService {

    private static final Logger log = LoggerFactory.getLogger(JsonlParserService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final S3Client s3Client;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    public JsonlParserService(
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName,
            ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
    }

    /**
     * Downloads and parses a JSONL file from S3.
     *
     * @param s3Key S3 object key (may end with {@code .gz} for compressed files)
     * @return ordered list of parsed change records (malformed lines are skipped with warning)
     * @throws IOException           if the S3 read fails
     * @throws InvalidJsonlException if a required field ({@code op}, {@code k}, or {@code d}) is missing
     */
    public List<JsonlChangeRecord> parseFromS3(String s3Key) throws IOException {
        log.debug("Parsing JSONL file from S3: {}", s3Key);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        List<JsonlChangeRecord> records = new ArrayList<>();

        try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(request)) {
            InputStream inputStream = s3Response;
            if (s3Key.toLowerCase().endsWith(".gz")) {
                inputStream = new GZIPInputStream(s3Response);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmedLine = line.trim();
                    if (trimmedLine.isEmpty()) {
                        continue;
                    }

                    try {
                        Map<String, Object> raw = objectMapper.readValue(trimmedLine, MAP_TYPE_REF);
                        JsonlChangeRecord record = parseRecord(raw, lineNumber, s3Key);
                        records.add(record);
                    } catch (InvalidJsonlException e) {
                        // Fatal: missing required structural field — propagate immediately
                        throw e;
                    } catch (Exception e) {
                        log.warn("Skipping malformed JSONL line {}: s3Key={}, error={}",
                                lineNumber, s3Key, e.getMessage());
                    }
                }
            }
        }

        log.debug("Parsed {} records from JSONL file: {}", records.size(), s3Key);
        return records;
    }

    @SuppressWarnings("unchecked")
    private JsonlChangeRecord parseRecord(Map<String, Object> raw, int lineNumber, String s3Key) {
        Object opObj = raw.get("op");
        if (!(opObj instanceof String op)) {
            throw new InvalidJsonlException(
                    "Missing or non-string 'op' field at line " + lineNumber + " in " + s3Key);
        }

        Map<String, Object> key = null;
        Map<String, Object> data = null;

        switch (op) {
            case JsonlChangeRecord.OP_INSERT -> {
                Object dObj = raw.get("d");
                if (!(dObj instanceof Map)) {
                    throw new InvalidJsonlException(
                            "Missing 'd' field for INSERT at line " + lineNumber + " in " + s3Key);
                }
                data = (Map<String, Object>) dObj;
            }
            case JsonlChangeRecord.OP_UPDATE -> {
                Object kObj = raw.get("k");
                if (!(kObj instanceof Map)) {
                    throw new InvalidJsonlException(
                            "Missing 'k' field for UPDATE at line " + lineNumber + " in " + s3Key);
                }
                key = (Map<String, Object>) kObj;
                Object dObj = raw.get("d");
                if (dObj instanceof Map) {
                    data = (Map<String, Object>) dObj;
                }
            }
            case JsonlChangeRecord.OP_DELETE -> {
                Object kObj = raw.get("k");
                if (!(kObj instanceof Map)) {
                    throw new InvalidJsonlException(
                            "Missing 'k' field for DELETE at line " + lineNumber + " in " + s3Key);
                }
                key = (Map<String, Object>) kObj;
            }
            default -> throw new InvalidJsonlException(
                    "Unknown op '" + op + "' at line " + lineNumber + " in " + s3Key);
        }

        return new JsonlChangeRecord(op, key, data, lineNumber);
    }

    /**
     * Exception thrown when a required JSONL structural field is missing or invalid.
     * This is a fatal per-file error — processing of the current file stops.
     */
    public static class InvalidJsonlException extends RuntimeException {
        public InvalidJsonlException(String message) {
            super(message);
        }
    }
}
