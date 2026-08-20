package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.2a — the PG-type → Avro/Parquet schema mapper turns a site's stored {@code site_schemas}
 * table definition into a typed Avro record: scalar types map to the matching Avro type/logical type
 * and every column is a nullable union, the declared constraint notwithstanding (issue #237).
 * This is the typing the Parquet writers (T4.2b / T8) write against.
 */
class ParquetSchemaMapperTest {

    @Test
    void mapsScalarPostgresTypesToAvro() {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("code", "integer", false),
                col("name", "varchar(255)", false),
                col("notes", "text", false),
                col("price", "numeric(10,2)", false),
                col("rate", "double precision", false),
                col("active", "boolean", false),
                col("created_on", "date", false),
                col("created_at", "timestamp", false),
                col("blob", "bytea", false)),
                List.of(), List.of());

        Schema avro = ParquetSchemaMapper.toAvroSchema("customers", schema);

        assertEquals("customers", avro.getName());
        assertEquals(Schema.Type.LONG, branch(avro, "id").getType());
        assertEquals(Schema.Type.INT, branch(avro, "code").getType());
        assertEquals(Schema.Type.STRING, branch(avro, "name").getType());
        assertEquals(Schema.Type.STRING, branch(avro, "notes").getType());

        Schema price = branch(avro, "price");
        assertEquals(Schema.Type.BYTES, price.getType());
        LogicalType priceLogical = price.getLogicalType();
        assertTrue(priceLogical instanceof LogicalTypes.Decimal, "numeric → decimal logical type");
        assertEquals(10, ((LogicalTypes.Decimal) priceLogical).getPrecision());
        assertEquals(2, ((LogicalTypes.Decimal) priceLogical).getScale());

        assertEquals(Schema.Type.DOUBLE, branch(avro, "rate").getType());
        assertEquals(Schema.Type.BOOLEAN, branch(avro, "active").getType());

        Schema createdOn = branch(avro, "created_on");
        assertEquals(Schema.Type.INT, createdOn.getType());
        assertEquals(LogicalTypes.date(), createdOn.getLogicalType());

        Schema createdAt = branch(avro, "created_at");
        assertEquals(Schema.Type.LONG, createdAt.getType());
        assertEquals(LogicalTypes.timestampMicros(), createdAt.getLogicalType());

        assertEquals(Schema.Type.BYTES, branch(avro, "blob").getType());
    }

    /** Every checkpoint column is a nullable union, {@code NOT NULL} included. */
    @Test
    void everyColumnBecomesUnionWithNullWhateverTheDeclaredConstraint() {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("name", "varchar(50)", true)),
                List.of(), List.of());

        Schema avro = ParquetSchemaMapper.toAvroSchema("t", schema);

        for (String field : List.of("id", "name")) {
            Schema fieldSchema = avro.getField(field).schema();
            assertEquals(Schema.Type.UNION, fieldSchema.getType(), field + " is a union");
            assertTrue(fieldSchema.getTypes().stream().anyMatch(s -> s.getType() == Schema.Type.NULL),
                    field + " union contains null");
            assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType(),
                    field + " unions null first, so the null default is legal");
            assertEquals(org.apache.avro.JsonProperties.NULL_VALUE, avro.getField(field).defaultVal(),
                    field + " defaults to null");
        }

        assertEquals(Schema.Type.LONG, branch(avro, "id").getType(), "the value branch keeps the declared type");
        assertEquals(Schema.Type.STRING, branch(avro, "name").getType());
    }

    /**
     * Declared columns share {@code nullableColumn}; this comparison fails if the two artifacts
     * fork again.
     */
    @Test
    void checkpointAndDeltaSchemasAgreeThatEveryDeclaredColumnIsANullableUnion() {
        TableSchema schema = new TableSchema(List.of(
                col("id", "bigint", false),
                col("price", "numeric(12,2)", false),
                col("amount", "numeric", false),
                col("name", "varchar(50)", true)),
                List.of("id"), List.of());

        Schema checkpoint = ParquetSchemaMapper.toAvroSchema("t", schema);
        Schema delta = ParquetSchemaMapper.toDeltaAvroSchema("t", schema);

        for (String field : List.of("id", "price", "amount", "name")) {
            assertEquals(checkpoint.getField(field).schema(), delta.getField(field).schema(),
                    field + " must be the same nullable union in both artifacts");
            assertEquals(org.apache.avro.JsonProperties.NULL_VALUE, checkpoint.getField(field).defaultVal());
            assertEquals(org.apache.avro.JsonProperties.NULL_VALUE, delta.getField(field).defaultVal());
        }
        assertEquals(Schema.Type.STRING, delta.getField("_op").schema().getType(),
                "service columns stay required: a delta row always has an op");
        assertEquals(Schema.Type.LONG, delta.getField("_seq").schema().getType());
    }

    @Test
    void unknownTypeFallsBackToString() {
        TableSchema schema = new TableSchema(List.of(
                col("weird", "tsvector", false)),
                List.of(), List.of());

        Schema avro = ParquetSchemaMapper.toAvroSchema("t", schema);
        assertEquals(Schema.Type.STRING, branch(avro, "weird").getType());
    }

    @Test
    void bareNumericWithoutScaleMapsToStringToAvoidRounding() {
        // numeric/decimal with no declared precision/scale is arbitrary-scale in PostgreSQL; mapping
        // it to a fixed decimal(38,0) would silently round the fraction (123.45 -> 123). Carry it as a
        // lossless string instead. An explicitly scaled numeric still maps to a decimal logical type.
        TableSchema schema = new TableSchema(List.of(
                col("amount", "numeric", false),
                col("amount2", "decimal", false),
                col("priced", "numeric(10,2)", false)),
                List.of(), List.of());

        Schema avro = ParquetSchemaMapper.toAvroSchema("t", schema);

        assertEquals(Schema.Type.STRING, branch(avro, "amount").getType(),
                "bare numeric must not become a rounding decimal(38,0)");
        assertEquals(Schema.Type.STRING, branch(avro, "amount2").getType());
        assertTrue(branch(avro, "priced").getLogicalType() instanceof LogicalTypes.Decimal,
                "explicitly scaled numeric still maps to a decimal logical type");
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
}
