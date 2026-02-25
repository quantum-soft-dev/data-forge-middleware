package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * SQL generation strategy for {@code DBF} sites.
 *
 * <p>Compares consecutive CSV snapshots using {@link CsvDiffService} (Myers diff algorithm)
 * and generates INSERT / UPDATE / DELETE statements via {@link SqlStatementGenerator}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DbfSqlGenerationStrategy implements SqlGenerationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DbfSqlGenerationStrategy.class);

    private final CsvDiffService csvDiffService;
    private final SqlStatementGenerator sqlStatementGenerator;
    private final S3Client s3Client;
    private final String bucketName;
    private final MeterRegistry meterRegistry;

    public DbfSqlGenerationStrategy(
            CsvDiffService csvDiffService,
            SqlStatementGenerator sqlStatementGenerator,
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName,
            MeterRegistry meterRegistry) {
        this.csvDiffService = csvDiffService;
        this.sqlStatementGenerator = sqlStatementGenerator;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SqlGenerationResult generate(SqlGenerationContext context) throws IOException {
        StringBuilder sqlContent = new StringBuilder();
        int totalInserts = 0;
        int totalUpdates = 0;
        int totalDeletes = 0;
        int filesProcessed = 0;

        for (UploadedFile currentFile : context.relevantFiles()) {
            try {
                String normalizedName = normalizeFileName(currentFile.getOriginalFileName());
                String tableName = deriveTableName(normalizedName);

                log.debug("Processing CSV file: {} -> table {}", currentFile.getOriginalFileName(), tableName);

                String currentCsvContent = readCsvContentFromS3(currentFile.getS3Key());
                List<String> headers = extractHeaders(currentCsvContent);
                if (headers.isEmpty()) {
                    log.warn("Empty headers in CSV file: {}", currentFile.getOriginalFileName());
                    continue;
                }

                String previousCsvContent = "";
                UploadedFile previousFile = context.previousFilesMap().get(normalizedName);
                if (previousFile != null) {
                    previousCsvContent = readCsvContentFromS3(previousFile.getS3Key());
                }

                List<CsvRowDiff> diffs = csvDiffService.compare(previousCsvContent, currentCsvContent, headers);

                for (CsvRowDiff diff : diffs) {
                    String sql = sqlStatementGenerator.generate(diff, tableName, Map.of());
                    sqlContent.append(sql);

                    switch (diff.type()) {
                        case ADDED -> totalInserts++;
                        case MODIFIED -> totalUpdates++;
                        case DELETED -> totalDeletes++;
                    }
                }

                filesProcessed++;
                meterRegistry.counter("sql.generation.files.processed").increment();

            } catch (CsvDiffService.InvalidCsvHeaderException e) {
                log.warn("Skipping file {} due to invalid headers: {}",
                        currentFile.getOriginalFileName(), e.getMessage());
                meterRegistry.counter("sql.generation.files.skipped.invalid_headers").increment();
            }
        }

        if (sqlContent.isEmpty()) {
            return null;
        }

        return new SqlGenerationResult(
                sqlContent.toString(),
                new SqlGenerationStats(totalInserts, totalUpdates, totalDeletes, filesProcessed)
        );
    }

    private String readCsvContentFromS3(String s3Key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(request)) {
            InputStream inputStream = s3Response;
            if (s3Key.toLowerCase().endsWith(".gz")) {
                inputStream = new GZIPInputStream(s3Response);
            }

            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[8192];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, bytesRead);
                }
                String result = content.toString();
                // Strip UTF-8 BOM if present
                if (!result.isEmpty() && result.charAt(0) == '\uFEFF') {
                    result = result.substring(1);
                }
                return result;
            }
        }
    }

    private List<String> extractHeaders(String csvContent) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }
        try (CSVParser parser = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(new StringReader(csvContent))) {
            return new ArrayList<>(parser.getHeaderNames());
        }
    }

    private String normalizeFileName(String fileName) {
        if (fileName.toLowerCase().endsWith(".gz")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private String deriveTableName(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }
        return name;
    }
}
