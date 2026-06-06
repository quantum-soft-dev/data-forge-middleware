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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @TempDir
    Path tempDir;

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

        byte[] parquet = ParquetCheckpointWriter.toParquet("customers", schema, List.of(row1, row2));

        assertTrue(parquet.length > 0, "parquet bytes written");

        Path file = tempDir.resolve("customers.parquet");
        Files.write(file, parquet);

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

    /** The value branch of a field's schema (unwrapping a nullable union). */
    private static Schema branch(Schema record, String field) {
        Schema s = record.getField(field).schema();
        if (s.getType() == Schema.Type.UNION) {
            return s.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElseThrow();
        }
        return s;
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
