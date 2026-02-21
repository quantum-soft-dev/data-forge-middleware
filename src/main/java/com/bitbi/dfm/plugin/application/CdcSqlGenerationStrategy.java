package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * SQL generation strategy for {@code POSTGRES_CDC} sites.
 *
 * <p>Converts JSONL delta files directly to SQL INSERT / UPDATE / DELETE statements.
 * Uses the registered table schema to validate column names and for context.
 * PK-based WHERE clauses come from the explicit {@code k} field in each JSONL record.</p>
 *
 * <p>Per-file error handling:</p>
 * <ul>
 *   <li>Missing schema for a table → skip file with warning</li>
 *   <li>Unknown column in JSONL data (not in schema) → skip column with warning</li>
 *   <li>Missing {@code k} field on U/D record → {@link JsonlParserService.InvalidJsonlException} propagated</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class CdcSqlGenerationStrategy implements SqlGenerationStrategy {

    private static final Logger log = LoggerFactory.getLogger(CdcSqlGenerationStrategy.class);

    private final JsonlParserService jsonlParserService;
    private final SqlStatementGenerator sqlStatementGenerator;
    private final MeterRegistry meterRegistry;

    public CdcSqlGenerationStrategy(
            JsonlParserService jsonlParserService,
            SqlStatementGenerator sqlStatementGenerator,
            MeterRegistry meterRegistry) {
        this.jsonlParserService = jsonlParserService;
        this.sqlStatementGenerator = sqlStatementGenerator;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SqlGenerationResult generate(SqlGenerationContext context) throws IOException {
        StringBuilder sqlContent = new StringBuilder();
        int totalInserts = 0;
        int totalUpdates = 0;
        int totalDeletes = 0;
        int filesProcessed = 0;

        for (UploadedFile file : context.relevantFiles()) {
            String normalizedName = normalizeFileName(file.getOriginalFileName());
            String tableName = deriveTableName(normalizedName);
            TableSchema schema = context.tableSchemas().get(tableName);

            if (schema == null) {
                log.warn("No schema found for table '{}', skipping file: batchId={}, file={}",
                        tableName, context.batchId(), file.getOriginalFileName());
                meterRegistry.counter("sql.generation.cdc.files.skipped.no_schema").increment();
                continue;
            }

            log.debug("Processing JSONL file: {} -> table {}", file.getOriginalFileName(), tableName);

            List<JsonlChangeRecord> records;
            try {
                records = jsonlParserService.parseFromS3(file.getS3Key());
            } catch (JsonlParserService.InvalidJsonlException e) {
                log.error("Aborting JSONL file processing — missing required field: file={}, batchId={}, error={}",
                        file.getOriginalFileName(), context.batchId(), e.getMessage());
                meterRegistry.counter("sql.generation.cdc.files.failed").increment();
                throw e;
            }

            for (JsonlChangeRecord record : records) {
                // Filter out columns not in schema (skip with warning per spec)
                JsonlChangeRecord filtered = filterUnknownColumns(record, schema, tableName);

                String sql = sqlStatementGenerator.generateFromJsonl(filtered, tableName);
                sqlContent.append(sql);

                switch (record.op()) {
                    case JsonlChangeRecord.OP_INSERT -> totalInserts++;
                    case JsonlChangeRecord.OP_UPDATE -> totalUpdates++;
                    case JsonlChangeRecord.OP_DELETE -> totalDeletes++;
                    default -> log.warn("Unknown op '{}' at line {} in {} — skipped",
                            record.op(), record.lineNumber(), file.getOriginalFileName());
                }
            }

            filesProcessed++;
            meterRegistry.counter("sql.generation.cdc.files.processed").increment();
        }

        if (sqlContent.isEmpty()) {
            return null;
        }

        return new SqlGenerationResult(
                sqlContent.toString(),
                new SqlGenerationStats(totalInserts, totalUpdates, totalDeletes, filesProcessed)
        );
    }

    /**
     * Filters data fields in a JSONL record to remove columns not present in the table schema.
     * Unknown columns in {@code k} (key fields) are not filtered — they come from JSONL and
     * represent PK values which should match the schema.
     */
    private JsonlChangeRecord filterUnknownColumns(JsonlChangeRecord record, TableSchema schema, String tableName) {
        if (record.data() == null) {
            return record;
        }

        java.util.LinkedHashMap<String, Object> filteredData = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.data().entrySet()) {
            if (schema.hasColumn(entry.getKey())) {
                filteredData.put(entry.getKey(), entry.getValue());
            } else {
                log.warn("Unknown column '{}' in JSONL data for table '{}' at line {} — excluded from SQL",
                        entry.getKey(), tableName, record.lineNumber());
            }
        }

        if (filteredData.size() == record.data().size()) {
            return record; // No columns filtered, return original
        }

        return new JsonlChangeRecord(record.op(), record.key(), filteredData, record.lineNumber());
    }

    private String normalizeFileName(String fileName) {
        if (fileName.toLowerCase().endsWith(".gz")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private String deriveTableName(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(".jsonl")) {
            name = name.substring(0, name.length() - 6);
        }
        name = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }
        return name;
    }
}
