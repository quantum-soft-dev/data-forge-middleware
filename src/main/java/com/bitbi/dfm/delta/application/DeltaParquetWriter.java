package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Renders one segment's change records for a table as a typed <b>delta</b> Parquet file
 * (Delta Client v2 — 022, Task 8).
 *
 * <p>The file carries the declared columns (typed via {@link ParquetSchemaMapper}, all nullable)
 * plus service columns {@code _op} (INSERT/UPDATE/DELETE), {@code _seq}, and {@code _changed}. Each
 * row merges the record's key and data maps, so DELETE rows carry their key columns and keyed UPDATE
 * rows their key + changed columns. {@code _changed} lists the columns an UPDATE actually carried,
 * so a consumer distinguishes a null cell that means "set to NULL" (column listed) from one that
 * means "unchanged" (not listed); it is null for INSERT (full row) and DELETE (key only). Consumers
 * apply the files sequentially by seq — a FULL_SNAPSHOT segment (all INSERT) is a full table.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class DeltaParquetWriter {

    private static final CompressionCodecName CODEC = CompressionCodecName.SNAPPY;

    private DeltaParquetWriter() {
    }

    /**
     * Write one table's slice of a segment to an in-memory delta Parquet file.
     *
     * @param tableName   table name (Parquet record name)
     * @param tableSchema the stored PG schema for the table
     * @param records     the segment's change records for this table, in seq order
     * @param rowGroupBytes the configured row-group budget ({@link DeltaParquetProperties})
     * @return Parquet file bytes
     */
    public static byte[] toDeltaParquet(String tableName, TableSchema tableSchema, List<ChangeRecord> records,
                                        long rowGroupBytes) {
        List<Map<String, Value>> cellRows = new ArrayList<>(records.size());
        for (ChangeRecord change : records) {
            Map<String, Value> cells = new LinkedHashMap<>(change.getKeyMap());
            cells.putAll(change.getDataMap());
            cellRows.add(cells);
        }
        Schema avro = ParquetCheckpointWriter.widenDecimalsToFit(
                ParquetSchemaMapper.toDeltaAvroSchema(tableName, tableSchema), cellRows);
        List<GenericRecord> rows = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            ChangeRecord change = records.get(i);
            GenericRecord row = new GenericData.Record(avro);
            row.put("_op", change.getOp().name());
            row.put("_seq", change.getSeq());
            // For an UPDATE, record which columns the change actually carried so a consumer can tell
            // "set to NULL" (listed, null cell) from "unchanged" (not listed, also a null cell). Full
            // rows (INSERT) and key-only rows (DELETE) leave _changed null.
            row.put("_changed", change.getOp() == com.bitbi.dfm.delta.grpc.v2.Op.UPDATE
                    ? String.join(",", change.getDataMap().keySet())
                    : null);
            Map<String, Value> cells = cellRows.get(i);
            for (ColumnDefinition column : tableSchema.columns()) {
                row.put(column.name(), ParquetCheckpointWriter.coerceValue(
                        cells.get(column.name()), avro.getField(column.name()).schema()));
            }
            rows.add(row);
        }
        return ParquetCheckpointWriter.write(avro, rows, tableName, rowGroupBytes);
    }

    /**
     * Build one table's unified batch artifact with bounded heap use, refusing to put more than
     * {@code maxBytes} on local disk. The replay source is invoked once to write rows to the file,
     * preceded by a scan pass <em>only</em> when the table declares decimal columns whose precision
     * has to be measured losslessly. Records for other tables are ignored, allowing callers to
     * replay mixed-table segments.
     *
     * @throws ArtifactSizeLimitExceededException as soon as the next write would cross the limit
     * @throws ScratchBudgetExceededException      when the shared scratch directory has no room
     */
    public static FileWriteResult writeDeltaParquet(Path output, String tableName, TableSchema tableSchema,
                                                    RecordReplay replay, long maxBytes, long rowGroupBytes,
                                                    ScratchLease lease) {
        Schema base = ParquetSchemaMapper.toDeltaAvroSchema(tableName, tableSchema);
        Schema avro = widenDecimals(base, scanDecimalPrecisions(base, tableName, replay));
        AtomicLong rowCount = new AtomicLong();
        AtomicLong previousSeq = new AtomicLong(Long.MIN_VALUE);

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(
                        new FileOutputFile(output, maxBytes, lease))
                .withSchema(avro)
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .withRowGroupSize(rowGroupBytes)
                .withCompressionCodec(CODEC)
                .build()) {
            replay.forEach(change -> {
                if (!tableName.equals(change.getTable())) {
                    return;
                }
                long prior = previousSeq.getAndSet(change.getSeq());
                if (change.getSeq() <= prior) {
                    throw new IllegalArgumentException("Out-of-order sequence for table " + tableName
                            + ": " + change.getSeq() + " after " + prior);
                }
                try {
                    writer.write(toRecord(avro, tableSchema, change));
                    rowCount.incrementAndGet();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write Parquet row for table " + tableName, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Parquet file for table " + tableName, e);
        }

        try {
            return new FileWriteResult(rowCount.get(), Files.size(output), sha256(output));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect Parquet file for table " + tableName, e);
        }
    }

    /**
     * Build every requested table from one shared write replay. When any request declares a
     * decimal column, one shared scan replay first measures every decimal envelope. A render or
     * output failure is isolated to its table; a failure in the replay source itself is propagated
     * because no table can prove that it saw the complete changelog.
     */
    static BatchWriteResult writeBatchDeltaParquet(Map<String, TableWriteRequest> requests,
                                                    RecordReplay replay, long maxBytes,
                                                    long rowGroupBytes) {
        return writeBatchDeltaParquet(requests, replay, maxBytes, rowGroupBytes, null);
    }

    static BatchWriteResult writeBatchDeltaParquet(Map<String, TableWriteRequest> requests,
                                                    RecordReplay replay, long maxBytes,
                                                    long rowGroupBytes, PhaseClock clock) {
        if (requests.isEmpty()) {
            return new BatchWriteResult(Map.of(), Map.of());
        }
        Map<String, Schema> baseSchemas = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> decimalPrecisions = new LinkedHashMap<>();
        Map<String, FileWriteFailure> failures = new LinkedHashMap<>();
        boolean needsDecimalScan = false;
        for (Map.Entry<String, TableWriteRequest> entry : requests.entrySet()) {
            try {
                Schema base = ParquetSchemaMapper.toDeltaAvroSchema(
                        entry.getKey(), entry.getValue().schema());
                Map<String, Integer> precisions = declaredDecimalPrecisions(base);
                baseSchemas.put(entry.getKey(), base);
                decimalPrecisions.put(entry.getKey(), precisions);
                needsDecimalScan |= !precisions.isEmpty();
            } catch (RuntimeException e) {
                failures.put(entry.getKey(), failure(e));
            }
        }

        if (needsDecimalScan) {
            long[] scanNanos = {0L};
            try {
                replay.forEach(change -> {
                    Schema schema = baseSchemas.get(change.getTable());
                    if (schema == null || failures.containsKey(change.getTable())) {
                        return;
                    }
                    long started = System.nanoTime();
                    try {
                        updateDecimalPrecisions(schema, decimalPrecisions.get(change.getTable()), change);
                    } catch (RuntimeException e) {
                        failures.put(change.getTable(), failure(e));
                    } finally {
                        scanNanos[0] += System.nanoTime() - started;
                    }
                });
            } finally {
                if (clock != null) {
                    clock.addDecimalScan(scanNanos[0]);
                }
            }
        }

        Map<String, TableWriter> writers = new LinkedHashMap<>();
        for (Map.Entry<String, TableWriteRequest> entry : requests.entrySet()) {
            String tableName = entry.getKey();
            if (failures.containsKey(tableName)) {
                continue;
            }
            try {
                Schema avro = widenDecimals(baseSchemas.get(tableName), decimalPrecisions.get(tableName));
                writers.put(tableName, new TableWriter(tableName, entry.getValue(), avro, maxBytes,
                        rowGroupBytes));
            } catch (RuntimeException | IOException e) {
                failures.put(tableName, failure(e));
            }
        }

        RuntimeException replayFailure = null;
        long[] writeNanos = {0L};
        try {
            replay.forEach(change -> {
                TableWriter writer = writers.get(change.getTable());
                if (writer == null || failures.containsKey(change.getTable())) {
                    return;
                }
                long started = System.nanoTime();
                try {
                    writer.write(change);
                } catch (RuntimeException e) {
                    failures.put(change.getTable(), failure(e));
                } finally {
                    writeNanos[0] += System.nanoTime() - started;
                }
            });
        } catch (RuntimeException e) {
            replayFailure = e;
        }

        Map<String, FileWriteResult> files = new LinkedHashMap<>();
        long closeStarted = System.nanoTime();
        for (Map.Entry<String, TableWriter> entry : writers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException | IOException e) {
                failures.putIfAbsent(entry.getKey(), failure(e));
            }
        }
        writeNanos[0] += System.nanoTime() - closeStarted;
        if (clock != null) {
            clock.addWrite(writeNanos[0]);
        }
        for (Map.Entry<String, TableWriter> entry : writers.entrySet()) {
            String tableName = entry.getKey();
            if (replayFailure == null && !failures.containsKey(tableName)) {
                try {
                    files.put(tableName, entry.getValue().result());
                } catch (RuntimeException e) {
                    failures.put(tableName, failure(e));
                }
            }
        }
        if (replayFailure != null) {
            throw replayFailure;
        }
        return new BatchWriteResult(Map.copyOf(files), Map.copyOf(failures));
    }

    private static Map<String, Integer> scanDecimalPrecisions(Schema schema, String tableName,
                                                               RecordReplay replay) {
        Map<String, Integer> precisions = declaredDecimalPrecisions(schema);
        if (precisions.isEmpty()) {
            // Nothing to measure: skipping the pass halves the segment downloads for a table
            // without decimal columns, and finalization replays the changelog once per table.
            return precisions;
        }
        replay.forEach(change -> {
            if (tableName.equals(change.getTable())) {
                updateDecimalPrecisions(schema, precisions, change);
            }
        });
        return precisions;
    }

    private static Map<String, Integer> declaredDecimalPrecisions(Schema schema) {
        Map<String, Integer> precisions = new LinkedHashMap<>();
        for (Schema.Field field : schema.getFields()) {
            Schema fieldType = branch(field.schema());
            if (fieldType.getLogicalType() instanceof org.apache.avro.LogicalTypes.Decimal decimal) {
                precisions.put(field.name(), decimal.getPrecision());
            }
        }
        return precisions;
    }

    private static void updateDecimalPrecisions(Schema schema, Map<String, Integer> precisions,
                                                ChangeRecord change) {
        for (Schema.Field field : schema.getFields()) {
            Schema fieldType = branch(field.schema());
            if (!(fieldType.getLogicalType() instanceof org.apache.avro.LogicalTypes.Decimal decimal)) {
                continue;
            }
            Value value = change.getDataMap().containsKey(field.name())
                    ? change.getDataMap().get(field.name())
                    : change.getKeyMap().get(field.name());
            Object java = value == null ? null : ValueMapper.toJava(value);
            if (java != null) {
                int required = new BigDecimal(java.toString())
                        .setScale(decimal.getScale(), RoundingMode.HALF_UP).precision();
                precisions.merge(field.name(), required, Math::max);
            }
        }
    }

    private static Schema widenDecimals(Schema recordSchema, Map<String, Integer> precisions) {
        List<Schema.Field> fields = new ArrayList<>(recordSchema.getFields().size());
        for (Schema.Field field : recordSchema.getFields()) {
            Schema fieldSchema = field.schema();
            Schema valueSchema = branch(fieldSchema);
            if (valueSchema.getLogicalType() instanceof org.apache.avro.LogicalTypes.Decimal decimal) {
                int precision = Math.max(decimal.getPrecision(), precisions.getOrDefault(
                        field.name(), decimal.getPrecision()));
                if (precision > decimal.getPrecision()) {
                    Schema wider = org.apache.avro.LogicalTypes.decimal(precision, decimal.getScale())
                            .addToSchema(Schema.create(Schema.Type.BYTES));
                    fieldSchema = fieldSchema.getType() == Schema.Type.UNION
                            ? Schema.createUnion(Schema.create(Schema.Type.NULL), wider)
                            : wider;
                }
            }
            fields.add(new Schema.Field(field.name(), fieldSchema, field.doc(), field.defaultVal()));
        }
        return Schema.createRecord(recordSchema.getName(), recordSchema.getDoc(),
                recordSchema.getNamespace(), recordSchema.isError(), fields);
    }

    private static GenericRecord toRecord(Schema avro, TableSchema tableSchema, ChangeRecord change) {
        GenericRecord row = new GenericData.Record(avro);
        row.put("_op", change.getOp().name());
        row.put("_seq", change.getSeq());
        row.put("_changed", change.getOp() == com.bitbi.dfm.delta.grpc.v2.Op.UPDATE
                ? String.join(",", change.getDataMap().keySet())
                : null);
        for (ColumnDefinition column : tableSchema.columns()) {
            Value value = change.getDataMap().containsKey(column.name())
                    ? change.getDataMap().get(column.name())
                    : change.getKeyMap().get(column.name());
            row.put(column.name(), ParquetCheckpointWriter.coerceValue(
                    value, avro.getField(column.name()).schema()));
        }
        return row;
    }

    private static Schema branch(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            return schema.getTypes().stream()
                    .filter(type -> type.getType() != Schema.Type.NULL)
                    .findFirst().orElseThrow();
        }
        return schema;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    public interface RecordReplay {
        void forEach(Consumer<ChangeRecord> consumer);
    }

    public record FileWriteResult(long rowCount, long fileSize, String checksum) {
    }

    /**
     * One table's scratch file, its schema and its share of the shared scratch directory.
     *
     * <p>The lease travels with the file rather than with the writer because the caller keeps the
     * file after every writer is closed — a batch build uploads them one at a time — so it is the
     * caller that closes the lease, in the same {@code finally} that deletes the file
     * (issue #150).</p>
     */
    record TableWriteRequest(Path output, TableSchema schema, ScratchLease lease) {
    }

    record FileWriteFailure(String error, boolean permanent) {
    }

    record BatchWriteResult(Map<String, FileWriteResult> files,
                            Map<String, FileWriteFailure> failures) {
    }

    private static FileWriteFailure failure(Throwable error) {
        Throwable cause = error;
        boolean permanent = false;
        while (cause != null) {
            if (cause instanceof ArtifactSizeLimitExceededException) {
                // Deterministically too large for its own ceiling: the artifact fails identically
                // on every retry, so the caller abandons it now instead of spending seven attempts.
                // A ScratchBudgetExceededException is deliberately NOT here (issue #150) — the
                // artifact is fine and the directory was busy, so it takes the ordinary backoff.
                permanent = true;
                break;
            }
            cause = cause.getCause();
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new FileWriteFailure(message, permanent);
    }

    private static final class TableWriter implements AutoCloseable {
        private final String tableName;
        private final TableWriteRequest request;
        private final Schema avro;
        private final ParquetWriter<GenericRecord> writer;
        private long rowCount;
        private long previousSeq = Long.MIN_VALUE;

        private TableWriter(String tableName, TableWriteRequest request, Schema avro, long maxBytes,
                            long rowGroupBytes) throws IOException {
            this.tableName = tableName;
            this.request = request;
            this.avro = avro;
            this.writer = AvroParquetWriter.<GenericRecord>builder(
                            new FileOutputFile(request.output(), maxBytes, request.lease()))
                    .withSchema(avro)
                    .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                    .withConf(new PlainParquetConfiguration())
                    .withRowGroupSize(rowGroupBytes)
                    .withCompressionCodec(CODEC)
                    .build();
        }

        private void write(ChangeRecord change) {
            if (change.getSeq() <= previousSeq) {
                throw new IllegalArgumentException("Out-of-order sequence for table " + tableName
                        + ": " + change.getSeq() + " after " + previousSeq);
            }
            previousSeq = change.getSeq();
            try {
                writer.write(toRecord(avro, request.schema(), change));
                rowCount++;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write Parquet row for table " + tableName, e);
            }
        }

        private FileWriteResult result() {
            try {
                return new FileWriteResult(rowCount, Files.size(request.output()), sha256(request.output()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to inspect Parquet file for table " + tableName, e);
            }
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
