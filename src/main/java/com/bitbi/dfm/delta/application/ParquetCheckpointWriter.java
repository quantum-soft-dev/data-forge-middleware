package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Renders a checkpoint table's folded state to a typed Parquet snapshot for Power BI egress
 * (Delta Client v2 — 022, Task 4, CR §12).
 *
 * <p>The Parquet schema is typed from {@code site_schemas} via {@link ParquetSchemaMapper}; each wire
 * {@link Value} is coerced to its <em>declared</em> column type rather than its oneof case, because
 * dates, timestamps and decimals arrive as ISO / decimal <b>strings</b> (client FR-004) and must be
 * parsed by the schema. Logical types (decimal, date, timestamp-micros) are written through the
 * standard Avro conversions ({@link #logicalTypeModel()}); a reader using the same model reads exact
 * {@link BigDecimal}/{@link LocalDate}/{@link Instant} values back. Writing is Hadoop-free
 * (in-memory {@link OutputFile} + {@link PlainParquetConfiguration}), mirroring the T4.1 smoke proof.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ParquetCheckpointWriter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ParquetCheckpointWriter.class);

    private static final CompressionCodecName CODEC = CompressionCodecName.SNAPPY;

    private ParquetCheckpointWriter() {
    }

    /**
     * Write the rows to an in-memory Parquet file typed from the table schema.
     *
     * @param tableName   table name (Parquet record name)
     * @param tableSchema the stored PG schema for the table
     * @param rows        each row's column → wire value (present columns only; absent = null cell)
     * @return Parquet file bytes
     */
    public static byte[] toParquet(String tableName, TableSchema tableSchema, List<Map<String, Value>> rows) {
        Schema avro = widenDecimalsToFit(ParquetSchemaMapper.toAvroSchema(tableName, tableSchema), rows);
        List<GenericRecord> records = new java.util.ArrayList<>(rows.size());
        for (Map<String, Value> row : rows) {
            records.add(toRecord(avro, tableSchema, row));
        }
        return write(avro, records, tableName);
    }

    /**
     * Write pre-built Avro records to an in-memory Parquet file (shared by the checkpoint and
     * delta writers).
     */
    static byte[] write(Schema avro, List<GenericRecord> records, String tableName) {
        InMemoryOutputFile output = new InMemoryOutputFile();
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(output)
                .withSchema(avro)
                .withDataModel(logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .withCompressionCodec(CODEC)
                .build()) {
            for (GenericRecord record : records) {
                writer.write(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Parquet file for table " + tableName, e);
        }
        return output.toByteArray();
    }

    /**
     * Coerce a wire value to the (possibly nullable-union) field schema's declared type.
     */
    static Object coerceValue(Value value, Schema fieldSchema) {
        return coerce(value, branch(fieldSchema));
    }

    /**
     * Widen declared decimal columns whose data does not fit their declared precision (a client
     * schema understating its data would otherwise fail the whole file: "Cannot encode decimal
     * with precision N as max precision M"). The declared scale is kept — values are rescaled to
     * it on write regardless — and the precision grows to the widest value actually present
     * (measured after that rescale). Shared by the checkpoint and delta writers.
     *
     * @param recordSchema the schema typed from the declared columns
     * @param rows         each row's column → wire value
     * @return the same schema, or a rebuilt one with widened decimal columns
     */
    static Schema widenDecimalsToFit(Schema recordSchema, List<Map<String, Value>> rows) {
        Map<String, Integer> widened = null;
        for (Schema.Field field : recordSchema.getFields()) {
            if (!(branch(field.schema()).getLogicalType() instanceof LogicalTypes.Decimal decimal)) {
                continue;
            }
            int needed = decimal.getPrecision();
            for (Map<String, Value> row : rows) {
                Value value = row.get(field.name());
                Object java = value == null ? null : ValueMapper.toJava(value);
                if (java == null) {
                    continue;
                }
                needed = Math.max(needed,
                        toBigDecimal(java).setScale(decimal.getScale(), RoundingMode.HALF_UP).precision());
            }
            if (needed > decimal.getPrecision()) {
                if (widened == null) {
                    widened = new java.util.LinkedHashMap<>();
                }
                widened.put(field.name(), needed);
                log.warn("Column {} of table {} declared decimal({},{}) but its data needs precision {} — "
                                + "widening the Parquet type (check the declared schema against the data)",
                        field.name(), recordSchema.getName(), decimal.getPrecision(), decimal.getScale(), needed);
            }
        }
        if (widened == null) {
            return recordSchema;
        }
        List<Schema.Field> fields = new java.util.ArrayList<>(recordSchema.getFields().size());
        for (Schema.Field field : recordSchema.getFields()) {
            Schema fieldSchema = field.schema();
            Integer precision = widened.get(field.name());
            if (precision != null) {
                LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(fieldSchema).getLogicalType();
                Schema wider = LogicalTypes.decimal(precision, decimal.getScale())
                        .addToSchema(Schema.create(Schema.Type.BYTES));
                fieldSchema = fieldSchema.getType() == Schema.Type.UNION
                        ? Schema.createUnion(Schema.create(Schema.Type.NULL), wider)
                        : wider;
            }
            fields.add(new Schema.Field(field.name(), fieldSchema, field.doc(), field.defaultVal()));
        }
        return Schema.createRecord(recordSchema.getName(), recordSchema.getDoc(),
                recordSchema.getNamespace(), recordSchema.isError(), fields);
    }

    /**
     * Avro data model carrying the logical-type conversions used on both write and read, so decimal /
     * date / timestamp-micros columns round-trip as typed Java values.
     */
    public static GenericData logicalTypeModel() {
        GenericData model = new GenericData();
        model.addLogicalTypeConversion(new Conversions.DecimalConversion());
        model.addLogicalTypeConversion(new TimeConversions.DateConversion());
        model.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());
        return model;
    }

    private static GenericRecord toRecord(Schema avro, TableSchema tableSchema, Map<String, Value> row) {
        GenericRecord record = new GenericData.Record(avro);
        for (ColumnDefinition column : tableSchema.columns()) {
            Schema fieldType = branch(avro.getField(column.name()).schema());
            record.put(column.name(), coerce(row.get(column.name()), fieldType));
        }
        return record;
    }

    private static Object coerce(Value value, Schema type) {
        Object java = value == null ? null : ValueMapper.toJava(value);
        if (java == null) {
            return null;
        }
        LogicalType logical = type.getLogicalType();
        if (logical instanceof LogicalTypes.Decimal decimal) {
            return toBigDecimal(java).setScale(decimal.getScale(), RoundingMode.HALF_UP);
        }
        if (logical instanceof LogicalTypes.Date) {
            return toLocalDate(java);
        }
        if (logical instanceof LogicalTypes.TimestampMicros) {
            return toInstant(java);
        }
        return switch (type.getType()) {
            case LONG -> toNumber(java).longValue();
            case INT -> toNumber(java).intValue();
            case FLOAT -> toNumber(java).floatValue();
            case DOUBLE -> toNumber(java).doubleValue();
            case BOOLEAN -> (java instanceof Boolean b) ? b : toBoolean(java.toString());
            case BYTES -> ByteBuffer.wrap(toBytes(java));
            default -> java.toString();
        };
    }

    private static Schema branch(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            return schema.getTypes().stream()
                    .filter(t -> t.getType() != Schema.Type.NULL)
                    .findFirst().orElseThrow();
        }
        return schema;
    }

    /**
     * Coerce a boolean sent as text. Accepts PostgreSQL's canonical forms and common variants;
     * {@code Boolean.parseBoolean} returned {@code false} for everything except "true", silently
     * turning a "t"/"f"/"1"/"0" column all-false (review r4). Unrecognized text throws (like the
     * strict date/decimal coercions), so CheckpointService's per-table catch skips just that table.
     */
    private static boolean toBoolean(String text) {
        return switch (text.trim().toLowerCase()) {
            case "t", "true", "1", "y", "yes" -> true;
            case "f", "false", "0", "n", "no" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: '" + text + "'");
        };
    }

    private static BigDecimal toBigDecimal(Object java) {
        if (java instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(java.toString());
    }

    private static Number toNumber(Object java) {
        if (java instanceof Number n) {
            return n;
        }
        return new BigDecimal(java.toString());
    }

    private static byte[] toBytes(Object java) {
        if (java instanceof byte[] bytes) {
            return bytes;
        }
        return java.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static LocalDate toLocalDate(Object java) {
        if (java instanceof LocalDate d) {
            return d;
        }
        if (java instanceof Number n) {
            return LocalDate.ofEpochDay(n.longValue());
        }
        return LocalDate.parse(java.toString());
    }

    private static Instant toInstant(Object java) {
        if (java instanceof Instant i) {
            return i;
        }
        String text = java.toString();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through to offset / local parsing
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to local-as-UTC parsing
        }
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }

    /**
     * Parquet {@link OutputFile} backed by a {@link ByteArrayOutputStream} — keeps the materialized
     * snapshot in memory so it can be handed straight to S3 (no temp file, no Hadoop FS).
     */
    private static final class InMemoryOutputFile implements OutputFile {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public PositionOutputStream create(long blockSizeHint) {
            return positionStream();
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) {
            buffer.reset();
            return positionStream();
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0L;
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }

        private PositionOutputStream positionStream() {
            return new PositionOutputStream() {
                private long pos = 0L;

                @Override
                public long getPos() {
                    return pos;
                }

                @Override
                public void write(int b) {
                    buffer.write(b);
                    pos++;
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                    pos += len;
                }
            };
        }
    }
}
