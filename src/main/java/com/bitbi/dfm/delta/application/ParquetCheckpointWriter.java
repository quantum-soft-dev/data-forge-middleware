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
import java.nio.file.Path;
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
 * ({@link OutputFile} + {@link PlainParquetConfiguration}), mirroring the T4.1 smoke proof.</p>
 *
 * <p>A checkpoint snapshot streams to a local file ({@link #writeParquet}, issue #112) so the peak
 * is one row-group buffer rather than the whole table; the small, seal-bounded per-segment egress
 * slice still renders in memory ({@link #write}).</p>
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
     * Stream the rows to a local Parquet file typed from the table schema.
     *
     * <p>Nothing but the current Parquet row group is held in heap (issue #112): the rows are
     * traversed, not collected, and the encoded file goes straight to {@code output}. The traversal
     * happens twice only when the table declares decimal columns whose envelope must be measured
     * losslessly before the schema is fixed.</p>
     *
     * @param output        the local file to write (truncated if it exists)
     * @param tableName     table name (Parquet record name)
     * @param tableSchema   the stored PG schema for the table
     * @param rows          each row's column → wire value (present columns only; absent = null cell)
     * @param maxBytes      refuse to put more than this many bytes on local disk
     * @param rowGroupBytes the configured row-group budget ({@link DeltaParquetProperties})
     * @param lease         this file's share of the shared scratch directory (issue #150)
     * @throws ArtifactSizeLimitExceededException as soon as the next write would cross {@code maxBytes}
     * @throws ScratchBudgetExceededException     when the shared scratch directory has no room
     */
    public static DecimalDegradeTally writeParquet(Path output, String tableName, TableSchema tableSchema,
                                    Iterable<Map<String, Value>> rows, long maxBytes, long rowGroupBytes,
                                    ScratchLease lease) {
        DecimalDegradeTally nonFinite = new DecimalDegradeTally();
        Schema avro = widenDecimalsToFit(ParquetSchemaMapper.toAvroSchema(tableName, tableSchema), rows);
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(
                        new FileOutputFile(output, maxBytes, lease))
                .withSchema(avro)
                .withDataModel(logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .withRowGroupSize(rowGroupBytes)
                .withCompressionCodec(CODEC)
                .build()) {
            for (Map<String, Value> row : rows) {
                writer.write(toRecord(avro, tableSchema, row, nonFinite));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Parquet file for table " + tableName, e);
        }
        warnDegraded(tableName, nonFinite);
        return nonFinite;
    }

    /**
     * Write pre-built Avro records to an in-memory Parquet file (shared by the checkpoint and
     * delta writers).
     */
    static byte[] write(Schema avro, List<GenericRecord> records, String tableName, long rowGroupBytes) {
        InMemoryOutputFile output = new InMemoryOutputFile();
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(output)
                .withSchema(avro)
                .withDataModel(logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .withRowGroupSize(rowGroupBytes)
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
        return coerceValue(value, fieldSchema, new DecimalDegradeTally());
    }

    /**
     * As {@link #coerceValue(Value, Schema)}, counting the cells this pipeline cannot store under
     * their declared type (issue #215).
     *
     * <p>The single coercion seam both Parquet writers share, which is why the tally is threaded
     * here rather than duplicated in each. A non-finite {@code numeric} -- {@code NaN},
     * {@code +/-Infinity}, all legal in PostgreSQL -- has no Parquet DECIMAL representation, so the
     * cell is written NULL; counting it here is what keeps that from being silent, since the value
     * is otherwise indistinguishable in the file from a column that really was NULL.</p>
     *
     * @param value       the wire value
     * @param fieldSchema the Avro field schema to coerce into
     * @param nonFinite   tally incremented once per degraded cell
     * @return the coerced value, or {@code null} when absent, NULL or unrepresentable
     */
    static Object coerceValue(Value value, Schema fieldSchema, DecimalDegradeTally nonFinite) {
        Schema destination = branch(fieldSchema);
        if (value != null && ValueMapper.isUnrepresentable(value)) {
            return coerceUnrepresentable(value, destination, nonFinite);
        }
        return coerce(value, destination, nonFinite);
    }

    /**
     * A decimal token {@link BigDecimal} cannot parse, placed against the column that has to hold it
     * (issue #215, review round 2).
     *
     * <p>Whether anything is lost depends on the <b>destination</b>, not on the token: a bare
     * {@code numeric}/{@code decimal} column is mapped to Avro STRING precisely so the value travels
     * losslessly in its on-the-wire form ({@code ParquetSchemaMapper}), and {@code double precision}
     * maps to Avro DOUBLE, which carries {@code NaN} and {@code ±Infinity} natively. Degrading at the
     * mapper — before the destination is known — wrote NULL over both, which is avoidable loss on a
     * column type that can hold the value. Only a DECIMAL destination genuinely cannot, and only that
     * is counted.</p>
     */
    private static Object coerceUnrepresentable(Value value, Schema destination,
                                                DecimalDegradeTally tally) {
        String token = value.getDecimalValue().trim();
        boolean nonFinite = ValueMapper.isNonFiniteDecimal(value);
        if (destination.getLogicalType() == null) {
            switch (destination.getType()) {
                case STRING -> {
                    return token;
                }
                case DOUBLE -> {
                    if (nonFinite) {
                        return nonFiniteDouble(token);
                    }
                }
                case FLOAT -> {
                    if (nonFinite) {
                        return (float) nonFiniteDouble(token);
                    }
                }
                default -> {
                    // LONG, INT, BOOLEAN, BYTES and the logical types below hold none of these.
                }
            }
        }
        if (nonFinite) {
            tally.nonFinite();
        } else {
            tally.malformed();
        }
        return null;
    }

    /**
     * Built from the token rather than {@code Double.parseDouble}, which rejects spellings
     * PostgreSQL accepts (it takes {@code inf}, and {@link ValueMapper} matches it).
     */
    private static double nonFiniteDouble(String token) {
        if (token.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        return token.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }

    /**
     * Widen declared decimal columns whose data does not fit their declared precision (a client
     * schema understating its data would otherwise fail the whole file: "Cannot encode decimal
     * with precision N as max precision M"). The declared scale is kept — values are rescaled to
     * it on write regardless — and the precision grows to the widest value actually present
     * (measured after that rescale). Shared by the checkpoint and delta writers.
     *
     * <p>The rows are traversed <b>once</b>, and not at all when no column declares a decimal, so a
     * caller may hand over a lazily iterated view of its state instead of a materialized list.</p>
     *
     * @param recordSchema the schema typed from the declared columns
     * @param rows         each row's column → wire value
     * @return the same schema, or a rebuilt one with widened decimal columns
     */
    static Schema widenDecimalsToFit(Schema recordSchema, Iterable<Map<String, Value>> rows) {
        Map<String, LogicalTypes.Decimal> declared = new java.util.LinkedHashMap<>();
        for (Schema.Field field : recordSchema.getFields()) {
            if (branch(field.schema()).getLogicalType() instanceof LogicalTypes.Decimal decimal) {
                declared.put(field.name(), decimal);
            }
        }
        if (declared.isEmpty()) {
            return recordSchema;
        }

        Map<String, Integer> needed = new java.util.LinkedHashMap<>();
        declared.forEach((name, decimal) -> needed.put(name, decimal.getPrecision()));
        for (Map<String, Value> row : rows) {
            for (Map.Entry<String, LogicalTypes.Decimal> column : declared.entrySet()) {
                Value value = row.get(column.getKey());
                Object java = value == null ? null : ValueMapper.toJava(value);
                if (java == null) {
                    continue;
                }
                BigDecimal measurable = toBigDecimal(java);
                if (measurable == null) {
                    continue;
                }
                needed.merge(column.getKey(), measurable
                        .setScale(column.getValue().getScale(), RoundingMode.HALF_UP).precision(), Math::max);
            }
        }

        Map<String, Integer> widened = null;
        for (Map.Entry<String, LogicalTypes.Decimal> column : declared.entrySet()) {
            LogicalTypes.Decimal decimal = column.getValue();
            int precision = needed.get(column.getKey());
            if (precision > decimal.getPrecision()) {
                if (widened == null) {
                    widened = new java.util.LinkedHashMap<>();
                }
                widened.put(column.getKey(), precision);
                log.warn("Column {} of table {} declared decimal({},{}) but its data needs precision {} — "
                                + "widening the Parquet type (check the declared schema against the data)",
                        column.getKey(), recordSchema.getName(), decimal.getPrecision(),
                        decimal.getScale(), precision);
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

    private static GenericRecord toRecord(Schema avro, TableSchema tableSchema, Map<String, Value> row,
                                          DecimalDegradeTally nonFinite) {
        GenericRecord record = new GenericData.Record(avro);
        for (ColumnDefinition column : tableSchema.columns()) {
            Schema fieldType = branch(avro.getField(column.name()).schema());
            Value cell = row.get(column.name());
            record.put(column.name(), coerceValue(cell, fieldType, nonFinite));
        }
        return record;
    }

    private static Object coerce(Value value, Schema type,
                                 DecimalDegradeTally nonFinite) {
        Object java = value == null ? null : ValueMapper.toJava(value);
        if (java == null) {
            return null;
        }
        LogicalType logical = type.getLogicalType();
        if (logical instanceof LogicalTypes.Decimal decimal) {
            BigDecimal exact = toBigDecimal(java);
            if (exact == null) {
                // Present, but with no DECIMAL representation: stored NULL and reported by the
                // caller's tally rather than thrown (issue #215). Reached by a non-finite double or
                // an unparseable string bound for a decimal column -- routes the wire-case check
                // cannot see, which is why the guard lives at the destination type.
                // Classified by the token, not by the Java type it arrived as: a legal PostgreSQL
                // NaN sent as a string_value is still non_finite, and calling it "malformed" would
                // page someone to chase a client defect that does not exist (review round 2).
                if (isNonFiniteText(java)) {
                    nonFinite.nonFinite();
                } else {
                    nonFinite.malformed();
                }
                return null;
            }
            return exact.setScale(decimal.getScale(), RoundingMode.HALF_UP);
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

    /**
     * One line per rendered table, not per cell: the workload that produces these writes them
     * continuously (a PostgreSQL numeric column cycling through NaN / +/-Infinity), so a line per
     * value would bury the log. The two reasons are reported separately because their remedies
     * differ -- see {@link DecimalDegradeTally}.
     */
    static void warnDegraded(String tableName, DecimalDegradeTally tally) {
        if (tally.nonFiniteCount() > 0) {
            log.warn("Table {}: {} decimal cell(s) written as NULL -- the value is NaN or "
                    + "+/-Infinity, which Parquet DECIMAL cannot represent (issue #215)",
                    tableName, tally.nonFiniteCount());
        }
        if (tally.malformedCount() > 0) {
            log.warn("Table {}: {} decimal cell(s) written as NULL -- the token could not be parsed "
                    + "as a decimal at all, which is a client defect rather than a value this "
                    + "pipeline cannot store (issue #215)",
                    tableName, tally.malformedCount());
        }
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

    /**
     * The decimal a column of that declared type can hold, or {@code null} when the value has no
     * DECIMAL representation at all (issue #215, review round 1).
     *
     * <p>The check belongs here rather than at the wire type: a non-finite value reaches a decimal
     * column by more routes than {@code decimal_value}. Protobuf {@code double} carries
     * {@code NaN} and {@code +/-Infinity} natively, and a {@code string_value} of {@code "NaN"} is
     * equally possible, so guarding only the decimal token left both of those throwing out of the
     * writer and costing the table its file -- the exact blast radius #215 exists to close.</p>
     */
    private static BigDecimal toBigDecimal(Object java) {
        if (java instanceof BigDecimal bd) {
            return bd;
        }
        if (java instanceof Double d && !Double.isFinite(d)) {
            return null;
        }
        if (java instanceof Float f && !Float.isFinite(f)) {
            return null;
        }
        if (java instanceof CharSequence text) {
            try {
                return new BigDecimal(text.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new BigDecimal(java.toString());
    }

    private static Number toNumber(Object java) {
        if (java instanceof Number n) {
            return n;
        }
        if (isNonFiniteText(java)) {
            // A `double precision` / `real` column can hold this; before #215's round 2 the
            // BigDecimal below threw and cost the table its file.
            return nonFiniteDouble(java.toString().trim());
        }
        return new BigDecimal(java.toString());
    }

    /** Whether this value is one of PostgreSQL's non-finite spellings carried as text. */
    private static boolean isNonFiniteText(Object java) {
        if (!(java instanceof CharSequence text)) {
            return java instanceof Double d ? !Double.isFinite(d)
                    : java instanceof Float f && !Float.isFinite(f);
        }
        String trimmed = text.toString().trim();
        String unsigned = trimmed.startsWith("+") || trimmed.startsWith("-")
                ? trimmed.substring(1) : trimmed;
        return trimmed.equalsIgnoreCase("nan")
                || unsigned.equalsIgnoreCase("infinity") || unsigned.equalsIgnoreCase("inf");
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
