package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T8.2 — the delta Parquet writer renders one segment's change records for a table as a typed
 * delta file: declared columns (all forced nullable — a DELETE of a keyed table carries only the
 * key) plus non-null {@code _op} (INSERT/UPDATE/DELETE) and {@code _seq}. Consumers apply the
 * files sequentially by seq; a FULL_SNAPSHOT segment (all INSERT) is a full table by construction.
 */
class DeltaParquetWriterTest {

    @TempDir
    Path tempDir;

    private static final TableSchema SCHEMA = new TableSchema(List.of(
            new ColumnDefinition("id", "bigint", false),
            new ColumnDefinition("name", "varchar(255)", true),
            new ColumnDefinition("price", "numeric(10,2)", false)),
            List.of("id"), List.of());

    @Test
    void writesOpAndSeqColumnsAlongsideTypedData() throws Exception {
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "name", strVal("Ann"), "price", decVal("12.50"))),
                change(Op.DELETE, 2L, Map.of("id", intVal(1)), Map.of()));

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records));

        assertEquals(2, rows.size());

        GenericRecord insert = rows.get(0);
        assertEquals("INSERT", insert.get("_op").toString());
        assertEquals(1L, insert.get("_seq"));
        assertEquals(1L, insert.get("id"));
        assertEquals("Ann", insert.get("name").toString());
        assertEquals(new BigDecimal("12.50"), insert.get("price"));

        // DELETE carries the key columns; absent data columns are null cells.
        GenericRecord delete = rows.get(1);
        assertEquals("DELETE", delete.get("_op").toString());
        assertEquals(2L, delete.get("_seq"));
        assertEquals(1L, delete.get("id"));
        assertNull(delete.get("name"));
        assertNull(delete.get("price"));
    }

    @Test
    void schemaTypesMatchDeclaredColumnsAndServiceColumnsAreNonNull() throws Exception {
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 7L, Map.of("id", intVal(3)),
                        Map.of("id", intVal(3), "name", strVal("Cid"), "price", decVal("1.00"))));

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records));
        Schema avro = rows.get(0).getSchema();

        assertEquals(Schema.Type.STRING, avro.getField("_op").schema().getType());
        assertEquals(Schema.Type.LONG, avro.getField("_seq").schema().getType());

        // Declared columns keep their logical types but are all nullable in the delta schema
        // (a keyed DELETE carries only key columns).
        assertEquals(Schema.Type.UNION, avro.getField("price").schema().getType());
        assertTrue(branch(avro, "price").getLogicalType() instanceof LogicalTypes.Decimal);
        assertEquals(Schema.Type.UNION, avro.getField("id").schema().getType());
        assertEquals(Schema.Type.LONG, branch(avro, "id").getType());
    }

    @Test
    void updateRowMergesKeyAndAfterImage() throws Exception {
        // Keyed UPDATE (future clients): data carries only changed columns; key columns still present.
        List<ChangeRecord> records = List.of(
                change(Op.UPDATE, 9L, Map.of("id", intVal(5)), Map.of("name", strVal("Renamed"))));

        GenericRecord row = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records)).get(0);

        assertEquals("UPDATE", row.get("_op").toString());
        assertEquals(5L, row.get("id"));
        assertEquals("Renamed", row.get("name").toString());
        assertNull(row.get("price"), "unchanged column absent -> null cell");
    }

    @Test
    void changedColumnListDistinguishesUnchangedFromSetNull() throws Exception {
        // An UPDATE sets name -> NULL while leaving price unchanged. Both render as null cells, so a
        // sequential consumer cannot tell "set NULL" from "unchanged" without help. The _changed
        // service column lists the columns actually carried in the UPDATE (review r4).
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "name", strVal("Ann"), "price", decVal("5.00"))),
                change(Op.UPDATE, 2L, Map.of("id", intVal(1)),
                        Map.of("name", nullVal())));

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records));

        // INSERT: full after-image, _changed is null (means "all columns present").
        assertNull(rows.get(0).get("_changed"), "INSERT carries the full row; _changed is null");

        // UPDATE: name was explicitly set (present, null) -> listed; price unchanged -> not listed.
        GenericRecord update = rows.get(1);
        assertEquals("UPDATE", update.get("_op").toString());
        assertEquals("name", update.get("_changed").toString(),
                "_changed lists only the columns carried in the UPDATE");
        assertNull(update.get("name"), "name set to NULL");
        assertNull(update.get("price"), "price unchanged (also a null cell, but not in _changed)");
    }

    @Test
    void widensDeclaredDecimalWhenDataExceedsItsPrecision() throws Exception {
        // The declared schema says numeric(7,2) but the data carries 9 significant digits (a client
        // schema understating its data — seen live: "Cannot encode decimal with precision 9 as max
        // precision 7" poisoned a whole segment). The writer widens the declared precision to fit
        // instead of failing the file.
        TableSchema narrow = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("price", "numeric(7,2)", false)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "price", decVal("1234567.89"))),
                change(Op.INSERT, 2L, Map.of("id", intVal(2)),
                        Map.of("id", intVal(2), "price", decVal("1.05"))));

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", narrow, records));

        assertEquals(new BigDecimal("1234567.89"), rows.get(0).get("price"));
        assertEquals(new BigDecimal("1.05"), rows.get(1).get("price"));
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(rows.get(0).getSchema(), "price").getLogicalType();
        assertEquals(9, decimal.getPrecision(), "declared precision widened to fit the data");
        assertEquals(2, decimal.getScale(), "declared scale is kept");
    }

    @Test
    void widensDecimalWhenScalingUpPushesPrecisionOverTheDeclaredLimit() throws Exception {
        // 9 digits at scale 0 declared as numeric(9,2): rescaling to 2 makes an 11-digit unscaled
        // value, which overflows the declared precision even though the raw digit count fit.
        TableSchema narrow = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("amount", "numeric(9,2)", false)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "amount", decVal("123456789"))));

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", narrow, records));

        assertEquals(new BigDecimal("123456789.00"), rows.get(0).get("amount"));
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(rows.get(0).getSchema(), "amount").getLogicalType();
        assertEquals(11, decimal.getPrecision());
        assertEquals(2, decimal.getScale());
    }

    @Test
    void streamsTwoPassesToAFileAndRoutesOnlyTheTargetTable() throws Exception {
        TableSchema narrow = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("price", "numeric(7,2)", false)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                changeForTable("customers", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "price", decVal("1234567.89"))),
                changeForTable("orders", Op.INSERT, 2L, Map.of(), Map.of()),
                changeForTable("customers", Op.UPDATE, 3L, Map.of("id", intVal(1)),
                        Map.of("price", decVal("9.50"))),
                changeForTable("customers", Op.DELETE, 4L, Map.of("id", intVal(1)), Map.of()));
        AtomicInteger passes = new AtomicInteger();
        Path output = tempDir.resolve("customers-batch.parquet");

        DeltaParquetWriter.FileWriteResult result = DeltaParquetWriter.writeDeltaParquet(
                output, "customers", narrow, consumer -> {
                    passes.incrementAndGet();
                    records.forEach(consumer);
                });

        assertEquals(2, passes.get(), "decimal scan + file write; neither pass retains the dataset");
        assertEquals(3, result.rowCount());
        assertEquals(Files.size(output), result.fileSize());
        assertEquals(64, result.checksum().length(), "SHA-256 hex");

        List<GenericRecord> rows = readBack(output);
        assertEquals(List.of(1L, 3L, 4L), rows.stream().map(row -> (Long) row.get("_seq")).toList());
        assertEquals("price", rows.get(1).get("_changed").toString());
        assertEquals(new BigDecimal("1234567.89"), rows.get(0).get("price"));
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(rows.get(0).getSchema(), "price")
                .getLogicalType();
        assertEquals(9, decimal.getPrecision());
    }

    @Test
    void skipsTheDecimalScanPassWhenTheTableDeclaresNoDecimalColumn() throws Exception {
        TableSchema noDecimals = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                changeForTable("customers", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "name", strVal("Ann"))),
                changeForTable("customers", Op.INSERT, 2L, Map.of("id", intVal(2)),
                        Map.of("id", intVal(2), "name", strVal("Bob"))));
        AtomicInteger passes = new AtomicInteger();
        Path output = tempDir.resolve("customers-no-decimals.parquet");

        DeltaParquetWriter.FileWriteResult result = DeltaParquetWriter.writeDeltaParquet(
                output, "customers", noDecimals, consumer -> {
                    passes.incrementAndGet();
                    records.forEach(consumer);
                });

        assertEquals(1, passes.get(), "nothing to measure — the changelog is replayed once");
        assertEquals(2, result.rowCount());
        assertEquals(List.of(1L, 2L),
                readBack(output).stream().map(row -> (Long) row.get("_seq")).toList());
    }

    @Test
    void fileWriterRejectsOutOfOrderTargetSequences() {
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 2L, Map.of("id", intVal(2)), Map.of("id", intVal(2))),
                change(Op.INSERT, 1L, Map.of("id", intVal(1)), Map.of("id", intVal(1))));

        assertThrows(IllegalArgumentException.class, () -> DeltaParquetWriter.writeDeltaParquet(
                tempDir.resolve("out-of-order.parquet"), "customers", SCHEMA,
                consumer -> records.forEach(consumer)));
    }

    private List<GenericRecord> readBack(byte[] parquet) throws Exception {
        assertFalse(parquet.length == 0, "parquet bytes written");
        Path file = tempDir.resolve("delta.parquet");
        Files.write(file, parquet);
        return readBack(file);
    }

    private List<GenericRecord> readBack(Path file) throws Exception {
        List<GenericRecord> rows = new java.util.ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                rows.add(record);
            }
        }
        assertNotNull(rows);
        return rows;
    }

    private static Schema branch(Schema record, String field) {
        Schema s = record.getField(field).schema();
        if (s.getType() == Schema.Type.UNION) {
            return s.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElseThrow();
        }
        return s;
    }

    private static ChangeRecord change(Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return changeForTable("customers", op, seq, key, data);
    }

    private static ChangeRecord changeForTable(String table, Op op, long seq,
                                               Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }

    private static Value decVal(String v) {
        return Value.newBuilder().setDecimalValue(v).build();
    }

    private static Value nullVal() {
        return Value.newBuilder().setIsNull(true).build();
    }
}
