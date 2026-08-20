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
 * <p>Scalar PG types map to the matching Avro type / logical type; <b>every</b> column becomes a
 * {@code [null, T]} union with a null default, whatever its declared constraint (issue #237 — see
 * {@link #toAvroSchema}). Unknown types fall back to {@code string} (lossless, since values are also
 * carried as strings). The Avro schema is the typing the Parquet writer (T4.2b) and Power BI read
 * against.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ParquetSchemaMapper {

    private static final String NAMESPACE = "com.bitbi.dfm.delta.egress";

    private ParquetSchemaMapper() {
    }

    /**
     * Build an Avro record schema for a <b>checkpoint</b> table snapshot from its column definitions.
     *
     * <p>Every column is a {@code [null, T]} union with a null default, a {@code NOT NULL}
     * declaration notwithstanding — the same rule {@link #toDeltaAvroSchema} has always applied, for
     * the same underlying reason: this pipeline is not in a position to promise a non-null cell, and
     * a REQUIRED Parquet field turns a single unpromisable cell into the loss of the whole table's
     * snapshot (issue #237). Two ordinary routes break the promise, neither of them exotic:</p>
     *
     * <ul>
     *   <li>a non-finite or malformed decimal, which {@code ParquetCheckpointWriter} writes as NULL
     *       and counts rather than throwing (issue #215) — including in a bare {@code numeric}
     *       column, which maps to {@code string} rather than to a decimal logical type;</li>
     *   <li>a folded row an {@code UPDATE} with no prior {@code INSERT} seeded from its key columns
     *       plus the carried change, which by construction can lack a declared column entirely
     *       ({@code ChangelogFold#apply}).</li>
     * </ul>
     *
     * <p>In both cases parquet-avro answers {@code "Null-value for required field"}, which
     * {@code CheckpointService} can only read as "this table cannot be rendered": the snapshot key is
     * detached on the advancing seq (a 404 for Bit BI, Parquet Export and the Delta Sync download)
     * and one {@code materialize_attempts} is spent, deterministically, until the row gives up for
     * good (#149). The constraint is therefore not carried at all rather than carried until it fails
     * — the snapshot's predecessor, the gzipped CSV retired by #113, carried no nullability either,
     * and the delta and completed-batch artifacts the same consumers read never have.</p>
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
     * Build the Avro record schema for a <b>delta</b> Parquet file (Task 8): non-null {@code _op}
     * (INSERT/UPDATE/DELETE) and {@code _seq} service columns first, then every declared column as
     * a nullable union. Declared columns share {@link #nullableColumn} with {@link #toAvroSchema}
     * so the two artifacts cannot disagree the way they did before issue #237. A keyed DELETE
     * carries only its key columns and a keyed UPDATE only its after-image, which is why a delta
     * field was never in a position to be REQUIRED; the checkpoint snapshot has the same
     * limitation, just from different routes (a degraded decimal, a folded UPDATE with no prior
     * INSERT).
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
