package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.2b — the Parquet checkpoint writer renders a table's folded state to a typed Parquet file,
 * typed from {@code site_schemas} (via {@link ParquetSchemaMapper}) and coercing each wire
 * {@link Value} to the declared column type. Dates/decimals/timestamps arrive as ISO/decimal strings
 * (client FR-004) and are parsed by declared type; present-null survives as a null cell. The round-trip
 * read (same logical-type model) proves both the schema and the coerced values.
 */
class ParquetCheckpointWriterTest {

    private static final long ROW_GROUP_BYTES = 8L * 1024 * 1024;

    @TempDir
    Path tempDir;

    /**
     * PostgreSQL {@code numeric} holds {@code NaN} and {@code ±Infinity} and the extractor sends
     * them; Parquet DECIMAL cannot represent any of them. Before #215 the parse threw out of the
     * writer, `CheckpointService` caught it per table, and the table spent a
     * {@code materialize_attempts} towards #149's permanent give-up — so one such cell cost the
     * table its snapshot every night, for ever. The file must be written, the cell must be NULL,
     * and the count must come back so the caller can report it.
     */
    @Test
    void nonFiniteDecimalIsWrittenAsNullAndCounted() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("numeric_edge", "numeric(10,2)", true)),
                List.of("id"), List.of());

        List<Map<String, Value>> rows = new ArrayList<>();
        for (String token : new String[]{"Infinity", "-Infinity", "NaN"}) {
            Map<String, Value> row = new LinkedHashMap<>();
            row.put("id", intVal(rows.size() + 1));
            row.put("numeric_edge", decVal(token));
            rows.add(row);
        }
        Map<String, Value> finite = new LinkedHashMap<>();
        finite.put("id", intVal(4));
        finite.put("numeric_edge", decVal("12.50"));
        rows.add(finite);

        Path out = tempDir.resolve("non-finite.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(out, "t", schema, rows,
                Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(3L, degraded.nonFiniteCount(), "one per non-finite cell, and only those");
        assertEquals(0L, degraded.malformedCount(), "a legal source value is not a client defect");
        assertTrue(Files.size(out) > 0, "the file is written rather than the table skipped");
    }

    /**
     * The same logical value reaches a decimal column by more routes than {@code decimal_value}:
     * protobuf {@code double} carries {@code NaN} and {@code ±Infinity} natively, and a
     * {@code string_value} of {@code "NaN"} is equally possible. Guarding only the decimal token
     * left both of those throwing out of the writer and costing the table its file — #215's blast
     * radius, reached by a door the first fix did not close (review round 1).
     */
    @Test
    void nonFiniteReachingADecimalColumnByAnotherWireTypeIsAlsoDegraded() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("numeric_edge", "numeric(10,2)", true)),
                List.of("id"), List.of());

        Map<String, Value> viaDouble = new LinkedHashMap<>();
        viaDouble.put("id", intVal(1));
        viaDouble.put("numeric_edge", dblVal(Double.NaN));
        Map<String, Value> viaString = new LinkedHashMap<>();
        viaString.put("id", intVal(2));
        viaString.put("numeric_edge", strVal("-Infinity"));

        Path out = tempDir.resolve("cross-wire.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(out, "t", schema,
                List.of(viaDouble, viaString), Long.MAX_VALUE, ROW_GROUP_BYTES,
                TestScratchLeases.unbounded());

        assertEquals(2L, degraded.nonFiniteCount() + degraded.malformedCount(),
                "both wire shapes degrade rather than throwing");
        assertTrue(Files.size(out) > 0, "the file is written rather than the table skipped");
    }

    /**
     * A legal PostgreSQL `NaN` that arrived as a `string_value` is still non-finite. Classifying it
     * `malformed` — which the guide defines as "a client defect somebody has to fix" — would page
     * someone to chase a bug that does not exist (review round 2).
     */
    @Test
    void aNonFiniteSentAsTextIsNotCalledAClientDefect() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("numeric_edge", "numeric(10,2)", true)),
                List.of("id"), List.of());

        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        row.put("numeric_edge", strVal("NaN"));

        Path out = tempDir.resolve("text-nan.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(out, "t", schema,
                List.of(row), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(1L, degraded.nonFiniteCount());
        assertEquals(0L, degraded.malformedCount(), "a legal source value is not a client defect");
    }

    /**
     * A token {@code BigDecimal} cannot parse at all is a client defect, not a value this pipeline
     * cannot store, and the two are counted apart because their remedies differ. Before #215 it
     * threw and was therefore loud; degrading it silently would have traded one defect for a
     * quieter one (review round 1).
     */
    @Test
    void aMalformedDecimalIsCountedApartFromANonFiniteOne() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("numeric_edge", "numeric(10,2)", true)),
                List.of("id"), List.of());

        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        row.put("numeric_edge", decVal("12,50"));

        Path out = tempDir.resolve("malformed.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(out, "t", schema,
                List.of(row), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(1L, degraded.malformedCount(), "counted as a client defect");
        assertEquals(0L, degraded.nonFiniteCount(), "and not as a legal-but-unstorable value");
        assertTrue(Files.size(out) > 0);
    }

    /**
     * A {@code NOT NULL} decimal used to be a REQUIRED Parquet field, so the NULL #215 writes for a
     * non-finite cell threw before the tally returned and the table lost its snapshot.
     */
    @Test
    void nonFiniteDecimalInANotNullColumnStillMaterializesTheTable() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("price", "numeric(12,2)", false)),
                List.of("id"), List.of());

        Map<String, Value> degradedRow = new LinkedHashMap<>();
        degradedRow.put("id", intVal(1));
        degradedRow.put("price", decVal("NaN"));
        Map<String, Value> finiteRow = new LinkedHashMap<>();
        finiteRow.put("id", intVal(2));
        finiteRow.put("price", decVal("12.50"));

        Path file = tempDir.resolve("not-null-non-finite.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(degradedRow, finiteRow), Long.MAX_VALUE, ROW_GROUP_BYTES,
                TestScratchLeases.unbounded());

        assertEquals(1L, degraded.nonFiniteCount(), "the tally is returned rather than lost to a throw");
        assertEquals(0L, degraded.malformedCount());
        assertTrue(Files.size(file) > 0, "the table keeps its snapshot");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord first = reader.read();
            assertNull(first.get("price"), "the unrepresentable cell is NULL, not the row's absence");
            assertEquals(1L, first.get("id"), "and the rest of the row survives");
            assertEquals(new BigDecimal("12.50"), reader.read().get("price"),
                    "the other rows of the same column are unaffected");
        }
    }

    /**
     * A padded finite token is a value, not a defect. The writers share {@link ValueMapper}, so the
     * mapper's trim has to survive this path too: otherwise the cell is NULL in every Parquet
     * artifact and counted {@code reason=malformed} while the fold has already treated it as the
     * unpadded number (issue #240).
     */
    @Test
    void aPaddedFiniteDecimalIsWrittenAsTheNumberNotCountedMalformed() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("price", "numeric(12,2)", true)),
                List.of("id"), List.of());

        Map<String, Value> padded = new LinkedHashMap<>();
        padded.put("id", intVal(1));
        padded.put("price", decVal(" 12.50 "));
        Map<String, Value> unpadded = new LinkedHashMap<>();
        unpadded.put("id", intVal(2));
        unpadded.put("price", decVal("12.50"));

        Path file = tempDir.resolve("padded-finite.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(padded, unpadded), Long.MAX_VALUE, ROW_GROUP_BYTES,
                TestScratchLeases.unbounded());

        assertEquals(0L, degraded.malformedCount(),
                "whitespace around a finite decimal is not a client defect");
        assertEquals(0L, degraded.nonFiniteCount());

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            assertEquals(new BigDecimal("12.50"), reader.read().get("price"),
                    "the padded token is stored as the number, not NULL");
            assertEquals(new BigDecimal("12.50"), reader.read().get("price"));
        }
    }

    /** Same as the non-finite case; counted apart because only this one is a client defect. */
    @Test
    void malformedDecimalInANotNullColumnStillMaterializesTheTable() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("price", "numeric(12,2)", false)),
                List.of("id"), List.of());

        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        row.put("price", decVal("12,50"));

        Path file = tempDir.resolve("not-null-malformed.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(row), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(1L, degraded.malformedCount(), "counted as a client defect");
        assertEquals(0L, degraded.nonFiniteCount());
        assertTrue(Files.size(file) > 0, "the table keeps its snapshot");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            assertNull(reader.read().get("price"), "the unparseable cell is NULL, not a lost row");
        }
    }

    /**
     * A bare {@code numeric NOT NULL} maps to Avro STRING, not to a decimal logical type, yet is
     * still degraded to NULL. Unioning only decimal-typed fields would leave this one throwing.
     */
    @Test
    void nonFiniteInANotNullBareNumericColumnStillMaterializesTheTable() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("amount", "numeric", false)),
                List.of("id"), List.of());

        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        row.put("amount", decVal("Infinity"));

        Path file = tempDir.resolve("not-null-bare-numeric.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(row), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(1L, degraded.nonFiniteCount());
        assertTrue(Files.size(file) > 0, "a string-typed destination is degraded too, so it must tolerate NULL");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            assertNull(reader.read().get("amount"),
                    "the STRING destination is NULL, which a REQUIRED string field would have refused");
        }
    }

    /**
     * A folded {@code UPDATE} with no prior {@code INSERT} can omit a declared {@code NOT NULL}
     * column; the snapshot still materializes with a null cell rather than losing the table.
     */
    @Test
    void aFoldedRowMissingANotNullColumnStillMaterializesTheTable() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("name", "varchar(255)", false),
                col("price", "numeric(12,2)", false)),
                List.of("id"), List.of());

        Map<String, Value> partial = new LinkedHashMap<>();   // an UPDATE with no prior INSERT
        partial.put("id", intVal(1));
        partial.put("price", decVal("3.00"));

        Path file = tempDir.resolve("partial-row.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(partial), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(0L, degraded.nonFiniteCount() + degraded.malformedCount(),
                "an absent column is not a degraded cell and must not be counted as one");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord row = reader.read();
            assertNull(row.get("name"), "the absent column is a null cell, not a lost row");
            assertEquals(new BigDecimal("3.00"), row.get("price"));
        }
    }

    /**
     * {@code widenDecimalsToFit} reconstructs decimal fields and is the only remaining path that
     * can emit a REQUIRED decimal ({@code UNION ? [null, wider] : wider}). The cases above never
     * overflow the declared precision, so that reconstruction is a no-op for them.
     */
    @Test
    void aWidenedNotNullDecimalStillAcceptsADegradedCell() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("price", "numeric(7,2)", false)),
                List.of("id"), List.of());

        Map<String, Value> oversized = new LinkedHashMap<>();
        oversized.put("id", intVal(1));
        oversized.put("price", decVal("1234567.89"));
        Map<String, Value> nan = new LinkedHashMap<>();
        nan.put("id", intVal(2));
        nan.put("price", decVal("NaN"));

        Path file = tempDir.resolve("widened-not-null-nan.parquet");
        DecimalDegradeTally degraded = ParquetCheckpointWriter.writeParquet(file, "t", schema,
                List.of(oversized, nan), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(1L, degraded.nonFiniteCount());
        assertTrue(Files.size(file) > 0, "the table keeps its snapshot after the type widens");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord first = reader.read();
            assertEquals(new BigDecimal("1234567.89"), first.get("price"));
            Schema priceSchema = first.getSchema().getField("price").schema();
            assertEquals(Schema.Type.UNION, priceSchema.getType(),
                    "widenDecimalsToFit must keep the nullable union, not emit a REQUIRED wider decimal");
            LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(first.getSchema(), "price").getLogicalType();
            assertEquals(9, decimal.getPrecision(), "declared precision widened to fit the data");
            assertEquals(2, decimal.getScale());
            assertNull(reader.read().get("price"), "the degraded cell is NULL on the widened field");
        }
    }

    @Test
    void writesTypedParquetCoercingValuesByDeclaredType() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("code", "integer", false),
                col("name", "varchar(255)", true),
                col("price", "numeric(10,2)", false),
                col("rate", "double precision", false),
                col("active", "boolean", false),
                col("created_on", "date", false),
                col("created_at", "timestamp", false)),
                List.of("id"), List.of());

        Map<String, Value> row1 = new LinkedHashMap<>();
        row1.put("id", intVal(1));
        row1.put("code", intVal(42));
        row1.put("name", strVal("Ann"));
        row1.put("price", decVal("12.50"));
        row1.put("rate", dblVal(3.14));
        row1.put("active", boolVal(true));
        row1.put("created_on", strVal("2024-01-15"));
        row1.put("created_at", strVal("2024-01-15T10:30:00Z"));

        Map<String, Value> row2 = new LinkedHashMap<>();
        row2.put("id", intVal(2));
        row2.put("code", intVal(7));
        row2.put("name", nullVal());            // present-null
        row2.put("price", decVal("0.00"));
        row2.put("rate", dblVal(2.0));
        row2.put("active", boolVal(false));
        row2.put("created_on", strVal("2024-02-20"));
        row2.put("created_at", strVal("2024-02-20T00:00:00Z"));

        Path file = tempDir.resolve("customers.parquet");
        ParquetCheckpointWriter.writeParquet(file, "customers", schema, List.of(row1, row2),
                Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertTrue(Files.size(file) > 0, "parquet bytes written straight to the file");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {

            GenericRecord r1 = reader.read();
            assertNotNull(r1, "first row present");

            // Schema typing matches site_schemas (logical types preserved).
            Schema avro = r1.getSchema();
            assertEquals(Schema.Type.LONG, branch(avro, "id").getType());
            assertEquals(Schema.Type.INT, branch(avro, "code").getType());
            assertEquals(Schema.Type.STRING, branch(avro, "name").getType());
            assertTrue(branch(avro, "price").getLogicalType() instanceof LogicalTypes.Decimal,
                    "numeric → decimal logical");
            assertEquals(Schema.Type.DOUBLE, branch(avro, "rate").getType());
            assertEquals(Schema.Type.BOOLEAN, branch(avro, "active").getType());
            assertEquals(LogicalTypes.date(), branch(avro, "created_on").getLogicalType());
            assertEquals(LogicalTypes.timestampMicros(), branch(avro, "created_at").getLogicalType());

            // Coerced values round-trip under the same logical-type model.
            assertEquals(1L, r1.get("id"));
            assertEquals(42, r1.get("code"));
            assertEquals("Ann", r1.get("name").toString());
            assertEquals(new BigDecimal("12.50"), r1.get("price"));
            assertEquals(3.14d, r1.get("rate"));
            assertEquals(true, r1.get("active"));
            assertEquals(LocalDate.parse("2024-01-15"), r1.get("created_on"));
            assertEquals(Instant.parse("2024-01-15T10:30:00Z"), r1.get("created_at"));

            GenericRecord r2 = reader.read();
            assertNotNull(r2, "second row present");
            assertNull(r2.get("name"), "present-null column survives as a null cell");
            assertEquals(new BigDecimal("0.00"), r2.get("price"));
            assertEquals(false, r2.get("active"));

            assertNull(reader.read(), "exactly two rows");
        }
    }

    @Test
    void coercesPostgresTextBooleansTrueAndFalse() throws Exception {
        // A client that serializes a boolean column as its PG text form ("t"/"f") hit the string
        // fallback, where Boolean.parseBoolean returned false for BOTH — a silently all-false column
        // (review r4). "t"/"true"/"1" must be true; "f"/"false"/"0" must be false.
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false), col("active", "boolean", false)),
                List.of("id"), List.of());
        Map<String, Value> t = new LinkedHashMap<>();
        t.put("id", intVal(1));
        t.put("active", strVal("t"));
        Map<String, Value> f = new LinkedHashMap<>();
        f.put("id", intVal(2));
        f.put("active", strVal("f"));

        Path file = tempDir.resolve("bools.parquet");
        ParquetCheckpointWriter.writeParquet(file, "t", schema, List.of(t, f), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            assertEquals(true, reader.read().get("active"), "PG 't' must coerce to true");
            assertEquals(false, reader.read().get("active"), "PG 'f' must coerce to false");
        }
    }

    @Test
    void rejectsUnrecognizedBooleanTextInsteadOfSilentlyFalse() {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false), col("active", "boolean", false)),
                List.of("id"), List.of());
        Map<String, Value> bad = new LinkedHashMap<>();
        bad.put("id", intVal(1));
        bad.put("active", strVal("maybe"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> ParquetCheckpointWriter.writeParquet(tempDir.resolve("bad.parquet"), "t", schema,
                        List.of(bad), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded()),
                "an unrecognized boolean text must throw (per-table skip), not silently write false");
    }

    @Test
    void widensDeclaredDecimalWhenDataExceedsItsPrecision() throws Exception {
        // Same widening contract as the delta writer: a declared numeric(7,2) with 9-digit data must
        // not fail the file (a poison table would be skipped from every checkpoint) — the declared
        // precision widens to fit.
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false), col("price", "numeric(7,2)", false)),
                List.of("id"), List.of());
        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        row.put("price", decVal("1234567.89"));

        Path file = tempDir.resolve("widened.parquet");
        ParquetCheckpointWriter.writeParquet(file, "t", schema, List.of(row), Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord r = reader.read();
            assertEquals(new BigDecimal("1234567.89"), r.get("price"));
            LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(r.getSchema(), "price").getLogicalType();
            assertEquals(9, decimal.getPrecision(), "declared precision widened to fit the data");
            assertEquals(2, decimal.getScale(), "declared scale is kept");
        }
    }

    @Test
    void flushesRowGroupsAtTheConfiguredByteBudget() throws Exception {
        // The row-group buffer is what a writer holds in heap before it flushes, so the budget is
        // the only multiplier bounding peak memory once the file itself is off-heap. Same data,
        // two budgets: a small one must split the file, the parquet-mr-sized one must not.
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false), col("payload", "varchar(255)", false)),
                List.of("id"), List.of());
        List<Map<String, Value>> rows = highEntropyRows(4000);

        Path split = tempDir.resolve("split.parquet");
        ParquetCheckpointWriter.writeParquet(split, "t", schema, rows, Long.MAX_VALUE, 64L * 1024, TestScratchLeases.unbounded());
        Path single = tempDir.resolve("single.parquet");
        ParquetCheckpointWriter.writeParquet(single, "t", schema, rows, Long.MAX_VALUE, 128L * 1024 * 1024, TestScratchLeases.unbounded());

        assertTrue(rowGroupCount(split) > 1,
                "a 64 KiB budget must flush several row groups for ~1 MB of data");
        assertEquals(1, rowGroupCount(single), "a 128 MiB budget must keep one row group");
    }

    @Test
    void stopsWritingAsSoonAsTheConfiguredFileLimitIsCrossed() throws Exception {
        // The snapshot goes to local disk, so an unbounded table would fill the node instead of the
        // heap. Same guard the completed-batch writer uses: refuse during output, not afterwards.
        TableSchema schema = new TableSchema(List.of(col("id", "bigint", false)), List.of("id"), List.of());
        Map<String, Value> row = new LinkedHashMap<>();
        row.put("id", intVal(1));
        Path file = tempDir.resolve("bounded.parquet");

        org.junit.jupiter.api.Assertions.assertThrows(ArtifactSizeLimitExceededException.class,
                () -> ParquetCheckpointWriter.writeParquet(file, "t", schema, List.of(row), 8, ROW_GROUP_BYTES, TestScratchLeases.unbounded()));

        assertTrue(Files.size(file) <= 8,
                "the limit is a write guard, not a policy checked after the full file exists");
    }

    @Test
    void streamsTheRowsInsteadOfCopyingThemIntoRecords() throws Exception {
        // The rows are handed over as an Iterable and traversed, never collected: one pass to
        // measure the decimal envelope, one to write. A table with no decimal column is traversed
        // once. Anything higher means the writer is materializing a copy of the folded state.
        TableSchema withDecimal = new TableSchema(List.of(
                col("id", "bigint", false), col("price", "numeric(10,2)", false)),
                List.of("id"), List.of());
        TableSchema withoutDecimal = new TableSchema(List.of(
                col("id", "bigint", false), col("name", "varchar(255)", true)),
                List.of("id"), List.of());
        Map<String, Value> decimalRow = new LinkedHashMap<>();
        decimalRow.put("id", intVal(1));
        decimalRow.put("price", decVal("1.00"));
        Map<String, Value> plainRow = new LinkedHashMap<>();
        plainRow.put("id", intVal(1));
        plainRow.put("name", strVal("Ann"));

        CountingRows decimalRows = new CountingRows(List.of(decimalRow));
        ParquetCheckpointWriter.writeParquet(tempDir.resolve("decimal.parquet"), "t", withDecimal,
                decimalRows, Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());
        CountingRows plainRows = new CountingRows(List.of(plainRow));
        ParquetCheckpointWriter.writeParquet(tempDir.resolve("plain.parquet"), "t", withoutDecimal,
                plainRows, Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        assertEquals(2, decimalRows.traversals, "decimal envelope scan + write");
        assertEquals(1, plainRows.traversals, "nothing to measure — one traversal");
    }

    /** An {@link Iterable} that counts how many times it was traversed. */
    private static final class CountingRows implements Iterable<Map<String, Value>> {
        private final List<Map<String, Value>> rows;
        private int traversals;

        private CountingRows(List<Map<String, Value>> rows) {
            this.rows = rows;
        }

        @Override
        public java.util.Iterator<Map<String, Value>> iterator() {
            traversals++;
            return rows.iterator();
        }
    }

    /** Rows whose payload does not compress away, so the buffered size actually reaches a budget. */
    private static List<Map<String, Value>> highEntropyRows(int count) {
        java.util.Random random = new java.util.Random(112L);
        List<Map<String, Value>> rows = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] noise = new byte[150];
            random.nextBytes(noise);
            Map<String, Value> row = new LinkedHashMap<>();
            row.put("id", intVal(i));
            row.put("payload", strVal(java.util.Base64.getEncoder().encodeToString(noise)));
            rows.add(row);
        }
        return rows;
    }

    private static int rowGroupCount(Path file) throws Exception {
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(new LocalInputFile(file),
                             org.apache.parquet.ParquetReadOptions.builder(new PlainParquetConfiguration())
                                     .build())) {
            return reader.getRowGroups().size();
        }
    }

    /** The value branch of a field's schema (unwrapping a nullable union). */
    private static Schema branch(Schema record, String field) {
        Schema s = record.getField(field).schema();
        if (s.getType() == Schema.Type.UNION) {
            return s.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElseThrow();
        }
        return s;
    }

    /**
     * The bootstrap build (issue #292) cannot call {@link ParquetCheckpointWriter#writeParquet}: it
     * has no per-table row collection to hand it — the rows of eighty-odd tables arrive interleaved
     * from one pass over the local frame, so several tables must be open at once and fed row by row.
     * What must not happen is a second renderer: the schema, the coercion and the degradation tally
     * of {@code #215}/{@code #237}/{@code #240} have to be the ones the general path uses, or the
     * two artifacts of one site disagree about the same cell. So the streaming form is the pieces
     * {@code writeParquet} is itself composed of, and the proof is that the two produce the
     * <b>same bytes</b> — including the widened decimal envelope, which is the one part of the
     * schema that depends on the data rather than on the declaration.
     */
    @Test
    void anOpenTableFedRowByRowProducesTheSameFileAsWriteParquet() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("amount", "numeric(4,2)", true),
                col("label", "text", true)),
                List.of("id"), List.of());

        List<Map<String, Value>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Value> row = new LinkedHashMap<>();
            row.put("id", intVal(i));
            // 123456.78 needs precision 8 against the declared 4 — the envelope must widen, and it
            // can only do that after seeing every row.
            row.put("amount", decVal(i == 3 ? "123456.78" : (i == 4 ? "NaN" : "1." + i)));
            row.put("label", strVal("row-" + i));
            rows.add(row);
        }

        Path expectedFile = tempDir.resolve("expected.parquet");
        DecimalDegradeTally expected = ParquetCheckpointWriter.writeParquet(expectedFile, "t", schema,
                rows, Long.MAX_VALUE, ROW_GROUP_BYTES, TestScratchLeases.unbounded());

        ParquetCheckpointWriter.DecimalEnvelope envelope =
                ParquetCheckpointWriter.decimalEnvelope(ParquetSchemaMapper.toAvroSchema("t", schema));
        assertTrue(envelope.measuresAnything(), "a declared decimal column must be measured");
        rows.forEach(envelope::observe);

        Path streamedFile = tempDir.resolve("streamed.parquet");
        DecimalDegradeTally streamed;
        try (ParquetCheckpointWriter.OpenTable table = ParquetCheckpointWriter.openTable(
                streamedFile, "t", schema, envelope.widened(), Long.MAX_VALUE, ROW_GROUP_BYTES,
                TestScratchLeases.unbounded())) {
            for (Map<String, Value> row : rows) {
                table.write(row);
            }
            streamed = table.tally();
        }

        assertArrayEquals(Files.readAllBytes(expectedFile), Files.readAllBytes(streamedFile),
                "the streaming form must render byte-for-byte what writeParquet renders");
        assertEquals(expected.nonFiniteCount(), streamed.nonFiniteCount(), "same degradation tally");
        assertEquals(expected.malformedCount(), streamed.malformedCount(), "same degradation tally");
    }

    private static ColumnDefinition col(String name, String type, boolean nullable) {
        return new ColumnDefinition(name, type, nullable);
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value dblVal(double v) {
        return Value.newBuilder().setDoubleValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }

    private static Value boolVal(boolean v) {
        return Value.newBuilder().setBoolValue(v).build();
    }

    private static Value decVal(String v) {
        return Value.newBuilder().setDecimalValue(v).build();
    }

    private static Value nullVal() {
        return Value.newBuilder().setIsNull(true).build();
    }
}
