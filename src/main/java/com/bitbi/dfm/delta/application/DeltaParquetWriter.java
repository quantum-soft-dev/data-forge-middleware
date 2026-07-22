package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one segment's change records for a table as a typed <b>delta</b> Parquet file
 * (Delta Client v2 — 022, Task 8).
 *
 * <p>The file carries the declared columns (typed via {@link ParquetSchemaMapper}, all nullable)
 * plus service columns {@code _op} (INSERT/UPDATE/DELETE), {@code _seq}, and {@code _changed}. Each
 * row merges the record's key and data maps, so DELETE rows carry their key columns and keyed UPDATE
 * rows their key + changed columns. {@code _changed} lists the columns an UPDATE actually carried,
 * so a consumer distinguishes a null cell that means "set to NULL" (column listed) from one that
 * means "unchanged" (not listed); it is null for INSERT (full row) and DELETE (key only). Consumers
 * apply the files sequentially by seq — a FULL_SNAPSHOT segment (all INSERT) is a full table.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class DeltaParquetWriter {

    private DeltaParquetWriter() {
    }

    /**
     * Write one table's slice of a segment to an in-memory delta Parquet file.
     *
     * @param tableName   table name (Parquet record name)
     * @param tableSchema the stored PG schema for the table
     * @param records     the segment's change records for this table, in seq order
     * @return Parquet file bytes
     */
    public static byte[] toDeltaParquet(String tableName, TableSchema tableSchema, List<ChangeRecord> records) {
        Schema avro = ParquetSchemaMapper.toDeltaAvroSchema(tableName, tableSchema);
        List<GenericRecord> rows = new ArrayList<>(records.size());
        for (ChangeRecord change : records) {
            GenericRecord row = new GenericData.Record(avro);
            row.put("_op", change.getOp().name());
            row.put("_seq", change.getSeq());
            // For an UPDATE, record which columns the change actually carried so a consumer can tell
            // "set to NULL" (listed, null cell) from "unchanged" (not listed, also a null cell). Full
            // rows (INSERT) and key-only rows (DELETE) leave _changed null.
            row.put("_changed", change.getOp() == com.bitbi.dfm.delta.grpc.v2.Op.UPDATE
                    ? String.join(",", change.getDataMap().keySet())
                    : null);
            Map<String, Value> cells = new LinkedHashMap<>(change.getKeyMap());
            cells.putAll(change.getDataMap());
            for (ColumnDefinition column : tableSchema.columns()) {
                row.put(column.name(), ParquetCheckpointWriter.coerceValue(
                        cells.get(column.name()), avro.getField(column.name()).schema()));
            }
            rows.add(row);
        }
        return ParquetCheckpointWriter.write(avro, rows, tableName);
    }
}
