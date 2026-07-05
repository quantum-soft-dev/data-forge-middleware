package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps a site's stored PostgreSQL table schema ({@code site_schemas}) to a typed Avro record schema
 * for Parquet egress (Delta Client v2 — 022, Task 4).
 *
 * <p>Scalar PG types map to the matching Avro type / logical type; nullable columns become a
 * {@code [null, T]} union with a null default. Unknown types fall back to {@code string} (lossless,
 * since values are also carried as strings). The Avro schema is the typing the Parquet writer (T4.2b)
 * and Power BI read against.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ParquetSchemaMapper {

    private static final String NAMESPACE = "com.bitbi.dfm.delta.egress";

    private ParquetSchemaMapper() {
    }

    /**
     * Build an Avro record schema for a table from its column definitions.
     *
     * @param tableName   table name (a valid PG / Avro identifier)
     * @param tableSchema the stored schema
     * @return an Avro record schema
     */
    public static Schema toAvroSchema(String tableName, TableSchema tableSchema) {
        List<Schema.Field> fields = new ArrayList<>(tableSchema.columns().size());
        for (ColumnDefinition column : tableSchema.columns()) {
            Schema valueSchema = avroType(column.type());
            if (column.nullable()) {
                Schema union = Schema.createUnion(Schema.create(Schema.Type.NULL), valueSchema);
                fields.add(new Schema.Field(column.name(), union, null, Schema.Field.NULL_DEFAULT_VALUE));
            } else {
                fields.add(new Schema.Field(column.name(), valueSchema, null, null));
            }
        }
        return Schema.createRecord(tableName, null, NAMESPACE, false, fields);
    }

    /**
     * Build the Avro record schema for a <b>delta</b> Parquet file (Task 8): non-null {@code _op}
     * (INSERT/UPDATE/DELETE) and {@code _seq} service columns first, then every declared column —
     * all forced nullable regardless of the declared constraint, because a keyed DELETE carries
     * only its key columns and a keyed UPDATE only its after-image.
     *
     * @param tableName   table name (a valid PG / Avro identifier)
     * @param tableSchema the stored schema
     * @return an Avro record schema for delta rows
     */
    public static Schema toDeltaAvroSchema(String tableName, TableSchema tableSchema) {
        List<Schema.Field> fields = new ArrayList<>(tableSchema.columns().size() + 2);
        fields.add(new Schema.Field("_op", Schema.create(Schema.Type.STRING), null, null));
        fields.add(new Schema.Field("_seq", Schema.create(Schema.Type.LONG), null, null));
        for (ColumnDefinition column : tableSchema.columns()) {
            Schema union = Schema.createUnion(Schema.create(Schema.Type.NULL), avroType(column.type()));
            fields.add(new Schema.Field(column.name(), union, null, Schema.Field.NULL_DEFAULT_VALUE));
        }
        return Schema.createRecord(tableName, null, NAMESPACE, false, fields);
    }

    private static Schema avroType(String pgType) {
        String type = pgType.trim().toLowerCase(Locale.ROOT);
        String base = type;
        List<Integer> params = new ArrayList<>();
        int paren = type.indexOf('(');
        if (paren >= 0) {
            int close = type.indexOf(')', paren);
            base = type.substring(0, paren).trim();
            String inside = type.substring(paren + 1, close < 0 ? type.length() : close);
            for (String part : inside.split(",")) {
                try {
                    params.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // non-numeric modifier (e.g. timezone) — ignore
                }
            }
        }

        if (base.startsWith("timestamp")) {
            return LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
        }
        return switch (base) {
            case "bigint", "int8", "bigserial" -> Schema.create(Schema.Type.LONG);
            case "integer", "int", "int4", "serial", "smallint", "int2", "smallserial" -> Schema.create(Schema.Type.INT);
            case "boolean", "bool" -> Schema.create(Schema.Type.BOOLEAN);
            case "real", "float4" -> Schema.create(Schema.Type.FLOAT);
            case "double precision", "float8", "double" -> Schema.create(Schema.Type.DOUBLE);
            case "numeric", "decimal" -> {
                if (params.isEmpty()) {
                    // Bare numeric/decimal is arbitrary precision & scale; a fixed decimal(38,0) would
                    // round the fraction away. Carry it losslessly as a string (its on-the-wire form).
                    yield Schema.create(Schema.Type.STRING);
                }
                int precision = params.get(0);
                int scale = params.size() > 1 ? params.get(1) : 0;
                yield LogicalTypes.decimal(precision, scale).addToSchema(Schema.create(Schema.Type.BYTES));
            }
            case "date" -> LogicalTypes.date().addToSchema(Schema.create(Schema.Type.INT));
            case "bytea" -> Schema.create(Schema.Type.BYTES);
            case "varchar", "character varying", "char", "character", "bpchar", "text", "citext", "uuid" ->
                    Schema.create(Schema.Type.STRING);
            default -> Schema.create(Schema.Type.STRING);
        };
    }
}
