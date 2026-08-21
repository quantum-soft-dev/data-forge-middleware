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
 * <p>Scalar PG types map to the matching Avro type / logical type; every declared column becomes a
 * {@code [null, T]} union with a null default, whatever its declared constraint
 * ({@link #toAvroSchema}). Unknown types fall back to {@code string} (lossless, since values are also
 * carried as strings). The Avro schema is the typing the Parquet writers and Power BI read against.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ParquetSchemaMapper {

    private static final String NAMESPACE = "com.bitbi.dfm.delta.egress";

    private ParquetSchemaMapper() {
    }

    /**
     * Build an Avro record schema for a checkpoint table snapshot.
     *
     * <p>Every column is a {@code [null, T]} union with a null default, a {@code NOT NULL}
     * declaration notwithstanding. This pipeline cannot promise a non-null cell: a non-finite
     * or malformed decimal is written NULL, and a folded {@code UPDATE} with no prior
     * {@code INSERT} can omit a declared column. A REQUIRED field turns either into the loss of
     * the table's snapshot (issue #237).</p>
     *
     * @param tableName   table name (a valid PG / Avro identifier)
     * @param tableSchema the stored schema
     * @return an Avro record schema
     */
    public static Schema toAvroSchema(String tableName, TableSchema tableSchema) {
        List<Schema.Field> fields = new ArrayList<>(tableSchema.columns().size());
        for (ColumnDefinition column : tableSchema.columns()) {
            fields.add(nullableColumn(column));
        }
        return Schema.createRecord(tableName, null, NAMESPACE, false, fields);
    }

    /**
     * Build the Avro record schema for a delta Parquet file: non-null {@code _op} and {@code _seq}
     * first, then every declared column as a nullable union via {@link #nullableColumn} (shared
     * with {@link #toAvroSchema}). A keyed DELETE carries only its key columns and a keyed UPDATE
     * only its after-image.
     *
     * @param tableName   table name (a valid PG / Avro identifier)
     * @param tableSchema the stored schema
     * @return an Avro record schema for delta rows
     */
    public static Schema toDeltaAvroSchema(String tableName, TableSchema tableSchema) {
        List<Schema.Field> fields = new ArrayList<>(tableSchema.columns().size() + 3);
        fields.add(new Schema.Field("_op", Schema.create(Schema.Type.STRING), null, null));
        fields.add(new Schema.Field("_seq", Schema.create(Schema.Type.LONG), null, null));
        // _changed disambiguates a null cell in an UPDATE: a comma-separated list of the columns
        // carried in the change. A column NOT listed is unchanged (keep prior value); a listed
        // column with a null cell was explicitly set to SQL NULL. Null for INSERT (full row) and
        // DELETE (key only). Without this, "unchanged" and "set NULL" are indistinguishable (r4).
        Schema nullableString = Schema.createUnion(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.STRING));
        fields.add(new Schema.Field("_changed", nullableString, null, Schema.Field.NULL_DEFAULT_VALUE));
        for (ColumnDefinition column : tableSchema.columns()) {
            fields.add(nullableColumn(column));
        }
        return Schema.createRecord(tableName, null, NAMESPACE, false, fields);
    }

    /**
     * One {@code [null, T]} field. Shared by both artifacts so a {@code NOT NULL} declaration cannot
     * make only the checkpoint snapshot REQUIRED (issue #237).
     */
    private static Schema.Field nullableColumn(ColumnDefinition column) {
        Schema union = Schema.createUnion(Schema.create(Schema.Type.NULL), avroType(column.type()));
        return new Schema.Field(column.name(), union, null, Schema.Field.NULL_DEFAULT_VALUE);
    }

    /**
     * Whether a declared PostgreSQL type is materialised as a Parquet <strong>DECIMAL</strong>, which
     * is the one destination in this pipeline with no representation for {@code NaN} or
     * {@code ±Infinity} — {@code ParquetCheckpointWriter.toBigDecimal} answers {@code null} for such a
     * value whatever Java type it arrived as, so every Parquet artifact writes that cell NULL.
     *
     * <p>Answered by building the field's own Avro type rather than by re-reading the type string,
     * so it cannot drift from what the writers actually do: a <em>bare</em> {@code numeric} is Avro
     * STRING (it carries the token losslessly, non-finite included) and only a parametrised
     * {@code numeric(p,s)} is DECIMAL — a distinction a second parser would be free to get wrong.</p>
     *
     * <p>Read by the Bit BI SQL path (issue #233): a value the Parquet side must NULL cannot be
     * addressed by a WHERE clause the SQL side renders, so a record keyed on one is skipped rather
     * than emitted against a baseline row whose key cell is NULL.</p>
     *
     * @param pgType the declared PostgreSQL column type
     * @return {@code true} if the column materialises as a Parquet DECIMAL
     */
    public static boolean rendersAsParquetDecimal(String pgType) {
        try {
            return avroType(pgType).getLogicalType() instanceof LogicalTypes.Decimal;
        } catch (RuntimeException e) {
            // A declaration Avro refuses but PostgreSQL accepts -- numeric(2,5), numeric(5,-2),
            // numeric(0). That table already loses its Parquet artifacts entirely (the throw is
            // caught per table where the schema is built), so this answer decides nothing about a
            // baseline that does not exist -- while letting the exception out here would fail the
            // whole batch's SQL generation and leave the segment pending for an identical retry, a
            // poison route on the path #215 exists to keep un-poisonable.
            return false;
        }
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
