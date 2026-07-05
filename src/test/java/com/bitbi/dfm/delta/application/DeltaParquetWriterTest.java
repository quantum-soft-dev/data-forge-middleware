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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private List<GenericRecord> readBack(byte[] parquet) throws Exception {
        assertFalse(parquet.length == 0, "parquet bytes written");
        Path file = tempDir.resolve("delta.parquet");
        Files.write(file, parquet);
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
        return ChangeRecord.newBuilder().setTable("customers").setOp(op).setSeq(seq)
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
}
