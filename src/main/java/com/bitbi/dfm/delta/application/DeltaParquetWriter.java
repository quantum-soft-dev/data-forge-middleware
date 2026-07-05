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
 * plus non-null service columns {@code _op} (INSERT/UPDATE/DELETE) and {@code _seq}. Each row
 * merges the record's key and data maps, so DELETE rows carry their key columns and keyed UPDATE
 * rows their key + after-image; cells for absent columns are null. Consumers apply the files
 * sequentially by seq — a FULL_SNAPSHOT segment (all INSERT) is a full table by construction.</p>
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
