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
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
     * @return Parquet file bytes
     */
    public static byte[] toDeltaParquet(String tableName, TableSchema tableSchema, List<ChangeRecord> records) {
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
        return ParquetCheckpointWriter.write(avro, rows, tableName);
    }

    /**
     * Build one table's unified batch artifact with bounded heap use. The replay source is invoked
     * once to write rows to the file, preceded by a scan pass <em>only</em> when the table declares
     * decimal columns whose precision has to be measured losslessly.
     * Records for other tables are ignored, allowing callers to replay mixed-table segments.
     */
    public static FileWriteResult writeDeltaParquet(Path output, String tableName, TableSchema tableSchema,
                                                    RecordReplay replay) {
        return writeDeltaParquet(output, tableName, tableSchema, replay, Long.MAX_VALUE);
    }

    /**
     * Write a unified artifact while refusing to put more than {@code maxBytes} on local disk.
     *
     * @throws ArtifactSizeLimitExceededException as soon as the next write would cross the limit
     */
    public static FileWriteResult writeDeltaParquet(Path output, String tableName, TableSchema tableSchema,
                                                    RecordReplay replay, long maxBytes) {
        Schema base = ParquetSchemaMapper.toDeltaAvroSchema(tableName, tableSchema);
        Schema avro = widenDecimals(base, scanDecimalPrecisions(base, tableName, replay));
        AtomicLong rowCount = new AtomicLong();
        AtomicLong previousSeq = new AtomicLong(Long.MIN_VALUE);

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(
                        new FileOutputFile(output, maxBytes))
                .withSchema(avro)
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
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

    private static Map<String, Integer> scanDecimalPrecisions(Schema schema, String tableName,
                                                               RecordReplay replay) {
        Map<String, Integer> precisions = new LinkedHashMap<>();
        Map<String, org.apache.avro.LogicalTypes.Decimal> decimals = new LinkedHashMap<>();
        for (Schema.Field field : schema.getFields()) {
            Schema fieldType = branch(field.schema());
            if (fieldType.getLogicalType() instanceof org.apache.avro.LogicalTypes.Decimal decimal) {
                decimals.put(field.name(), decimal);
                precisions.put(field.name(), decimal.getPrecision());
            }
        }
        if (decimals.isEmpty()) {
            // Nothing to measure: skipping the pass halves the segment downloads for a table
            // without decimal columns, and finalization replays the changelog once per table.
            return precisions;
        }
        replay.forEach(change -> {
            if (!tableName.equals(change.getTable())) {
                return;
            }
            decimals.forEach((column, decimal) -> {
                Value value = change.getDataMap().containsKey(column)
                        ? change.getDataMap().get(column)
                        : change.getKeyMap().get(column);
                Object java = value == null ? null : ValueMapper.toJava(value);
                if (java != null) {
                    int required = new BigDecimal(java.toString())
                            .setScale(decimal.getScale(), RoundingMode.HALF_UP).precision();
                    precisions.merge(column, required, Math::max);
                }
            });
        });
        return precisions;
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

    /** Raised when a unified artifact would exceed its configured local-file policy. */
    public static final class ArtifactSizeLimitExceededException extends RuntimeException {
        private ArtifactSizeLimitExceededException(long maxBytes) {
            super("Artifact exceeds temp-file limit of " + maxBytes + " bytes");
        }
    }

    /** Minimal Hadoop-free Parquet output backed by a local file. */
    private static final class FileOutputFile implements OutputFile {
        private final Path path;
        private final long maxBytes;

        private FileOutputFile(Path path, long maxBytes) {
            this.path = path;
            this.maxBytes = maxBytes;
        }

        @Override
        public PositionOutputStream create(long blockSizeHint) throws IOException {
            return stream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
            return stream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0L;
        }

        private PositionOutputStream stream(OpenOption... options) throws IOException {
            OutputStream output = Files.newOutputStream(path, options);
            return new PositionOutputStream() {
                private long position;

                @Override
                public long getPos() {
                    return position;
                }

                @Override
                public void write(int value) throws IOException {
                    checkCapacity(1);
                    output.write(value);
                    position++;
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    checkCapacity(length);
                    output.write(bytes, offset, length);
                    position += length;
                }

                @Override
                public void flush() throws IOException {
                    output.flush();
                }

                @Override
                public void close() throws IOException {
                    output.close();
                }

                private void checkCapacity(int length) {
                    if (length > maxBytes - position) {
                        throw new ArtifactSizeLimitExceededException(maxBytes);
                    }
                }
            };
        }
    }
}
