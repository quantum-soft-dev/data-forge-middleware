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
import java.util.LinkedHashMap;
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

    private static final long ROW_GROUP_BYTES = 8L * 1024 * 1024;

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

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records, ROW_GROUP_BYTES));

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

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records, ROW_GROUP_BYTES));
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

        GenericRecord row = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records, ROW_GROUP_BYTES)).get(0);

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

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", SCHEMA, records, ROW_GROUP_BYTES));

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

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", narrow, records, ROW_GROUP_BYTES));

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

        List<GenericRecord> rows = readBack(DeltaParquetWriter.toDeltaParquet("customers", narrow, records, ROW_GROUP_BYTES));

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
                }, Long.MAX_VALUE, ROW_GROUP_BYTES);

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
                }, Long.MAX_VALUE, ROW_GROUP_BYTES);

        assertEquals(1, passes.get(), "nothing to measure — the changelog is replayed once");
        assertEquals(2, result.rowCount());
        assertEquals(List.of(1L, 2L),
                readBack(output).stream().map(row -> (Long) row.get("_seq")).toList());
    }

    @Test
    void writesEveryNonDecimalTableFromOneSharedReplay() throws Exception {
        TableSchema customersSchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"), List.of());
        TableSchema ordersSchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("customer_id", "bigint", false)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                changeForTable("customers", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "name", strVal("Ann"))),
                changeForTable("orders", Op.INSERT, 2L, Map.of("id", intVal(10)),
                        Map.of("id", intVal(10), "customer_id", intVal(1))),
                changeForTable("customers", Op.UPDATE, 3L, Map.of("id", intVal(1)),
                        Map.of("name", strVal("Anne"))),
                changeForTable("orders", Op.DELETE, 4L, Map.of("id", intVal(10)), Map.of()));
        AtomicInteger passes = new AtomicInteger();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("customers", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("customers.parquet"), customersSchema));
        requests.put("orders", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("orders.parquet"), ordersSchema));

        DeltaParquetWriter.BatchWriteResult result = DeltaParquetWriter.writeBatchDeltaParquet(
                requests, consumer -> {
                    passes.incrementAndGet();
                    records.forEach(consumer);
                }, Long.MAX_VALUE, ROW_GROUP_BYTES);

        assertEquals(1, passes.get(), "table count must not multiply raw changelog replay");
        assertTrue(result.failures().isEmpty());
        assertEquals(2, result.files().get("customers").rowCount());
        assertEquals(2, result.files().get("orders").rowCount());
        assertEquals(List.of(1L, 3L), readBack(requests.get("customers").output()).stream()
                .map(row -> (Long) row.get("_seq")).toList());
        assertEquals(List.of(2L, 4L), readBack(requests.get("orders").output()).stream()
                .map(row -> (Long) row.get("_seq")).toList());
    }

    @Test
    void sharesOneDecimalEnvelopePassAcrossAllTables() throws Exception {
        TableSchema moneySchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("amount", "numeric(7,2)", false)),
                List.of("id"), List.of());
        TableSchema namesSchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                changeForTable("payments", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "amount", decVal("1234567.89"))),
                changeForTable("customers", Op.INSERT, 2L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "name", strVal("Ann"))));
        AtomicInteger passes = new AtomicInteger();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("payments", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("payments.parquet"), moneySchema));
        requests.put("customers", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("customers-decimal-batch.parquet"), namesSchema));

        DeltaParquetWriter.BatchWriteResult result = DeltaParquetWriter.writeBatchDeltaParquet(
                requests, consumer -> {
                    passes.incrementAndGet();
                    records.forEach(consumer);
                }, Long.MAX_VALUE, ROW_GROUP_BYTES);

        assertEquals(2, passes.get(), "one shared decimal scan plus one shared write replay");
        assertTrue(result.failures().isEmpty());
        GenericRecord payment = readBack(requests.get("payments").output()).get(0);
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) branch(payment.getSchema(), "amount")
                .getLogicalType();
        assertEquals(9, decimal.getPrecision());
        assertEquals(new BigDecimal("1234567.89"), payment.get("amount"));
        assertEquals(1, result.files().get("customers").rowCount());
    }

    @Test
    void reportsDecimalScanAndWritePhasesSeparately() throws Exception {
        TableSchema moneySchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("amount", "numeric(7,2)", false)),
                List.of("id"), List.of());
        PhaseClock clock = new PhaseClock();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("payments", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("payments-phased.parquet"), moneySchema));

        DeltaParquetWriter.writeBatchDeltaParquet(
                requests,
                consumer -> consumer.accept(changeForTable("payments", Op.INSERT, 1L,
                        Map.of("id", intVal(1)),
                        Map.of("id", intVal(1), "amount", decVal("1.00")))),
                Long.MAX_VALUE,
                ROW_GROUP_BYTES,
                clock);

        assertTrue(clock.decimalScanAttempted());
        assertTrue(clock.decimalScanNanos() > 0L, "decimal scan is its own phase");
        assertTrue(clock.writeAttempted());
        assertTrue(clock.writeNanos() > 0L, "write/close is its own phase");
    }

    @Test
    void skipsDecimalScanPhaseWhenNoTableDeclaresDecimals() throws Exception {
        TableSchema namesSchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"), List.of());
        PhaseClock clock = new PhaseClock();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("customers", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("customers-phased.parquet"), namesSchema));

        DeltaParquetWriter.writeBatchDeltaParquet(
                requests,
                consumer -> consumer.accept(changeForTable("customers", Op.INSERT, 1L,
                        Map.of("id", intVal(1)), Map.of("id", intVal(1), "name", strVal("Ann")))),
                Long.MAX_VALUE,
                ROW_GROUP_BYTES,
                clock);

        assertFalse(clock.decimalScanAttempted());
        assertTrue(clock.writeAttempted());
        assertTrue(clock.writeNanos() > 0L);
    }

    @Test
    void recordsDecimalScanWhenTheScanReplayThrows() {
        TableSchema moneySchema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false),
                new ColumnDefinition("amount", "numeric(7,2)", false)),
                List.of("id"), List.of());
        PhaseClock clock = new PhaseClock();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("payments", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("payments-scan-throw.parquet"), moneySchema));

        assertThrows(IllegalStateException.class, () ->
                DeltaParquetWriter.writeBatchDeltaParquet(
                        requests,
                        consumer -> {
                            throw new IllegalStateException("scan source failed");
                        },
                        Long.MAX_VALUE,
                        ROW_GROUP_BYTES,
                        clock));

        assertTrue(clock.decimalScanAttempted());
        assertFalse(clock.writeAttempted());
    }

    @Test
    void isolatesAnOutOfOrderTableWhileOtherWritersFinish() throws Exception {
        TableSchema schema = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of());
        List<ChangeRecord> records = List.of(
                changeForTable("customers", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        Map.of("id", intVal(1))),
                changeForTable("orders", Op.INSERT, 3L, Map.of("id", intVal(3)),
                        Map.of("id", intVal(3))),
                changeForTable("orders", Op.INSERT, 2L, Map.of("id", intVal(2)),
                        Map.of("id", intVal(2))),
                changeForTable("customers", Op.INSERT, 4L, Map.of("id", intVal(4)),
                        Map.of("id", intVal(4))));
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("customers", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("customers-isolated.parquet"), schema));
        requests.put("orders", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("orders-out-of-order.parquet"), schema));

        DeltaParquetWriter.BatchWriteResult result = DeltaParquetWriter.writeBatchDeltaParquet(
                requests, consumer -> records.forEach(consumer), Long.MAX_VALUE, ROW_GROUP_BYTES);

        assertEquals(2, result.files().get("customers").rowCount());
        assertEquals(List.of(1L, 4L), readBack(requests.get("customers").output()).stream()
                .map(row -> (Long) row.get("_seq")).toList());
        assertFalse(result.files().containsKey("orders"));
        assertTrue(result.failures().get("orders").error().contains("Out-of-order sequence"));
        assertFalse(result.failures().get("orders").permanent());
    }

    @Test
    void isolatesAnInvalidDeclaredSchemaWhileOtherWritersFinish() throws Exception {
        TableSchema valid = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of());
        TableSchema reservedColumn = new TableSchema(List.of(
                new ColumnDefinition("_op", "varchar(20)", false)), List.of("_op"), List.of());
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        requests.put("customers", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("customers-valid-schema.parquet"), valid));
        requests.put("broken", new DeltaParquetWriter.TableWriteRequest(
                tempDir.resolve("broken-schema.parquet"), reservedColumn));

        DeltaParquetWriter.BatchWriteResult result = DeltaParquetWriter.writeBatchDeltaParquet(
                requests, consumer -> consumer.accept(changeForTable("customers", Op.INSERT, 1L,
                        Map.of("id", intVal(1)), Map.of("id", intVal(1)))), Long.MAX_VALUE,
                ROW_GROUP_BYTES);

        assertEquals(1, result.files().get("customers").rowCount());
        assertFalse(result.files().containsKey("broken"));
        assertNotNull(result.failures().get("broken"));
    }

    @Test
    void stopsWritingAsSoonAsTheConfiguredFileLimitIsCrossed() throws Exception {
        TableSchema noDecimals = new TableSchema(List.of(
                new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of());
        Path output = tempDir.resolve("bounded.parquet");

        assertThrows(ArtifactSizeLimitExceededException.class,
                () -> DeltaParquetWriter.writeDeltaParquet(output, "customers", noDecimals,
                        consumer -> consumer.accept(changeForTable("customers", Op.INSERT, 1L,
                                Map.of("id", intVal(1)), Map.of("id", intVal(1)))), 8, ROW_GROUP_BYTES));

        assertTrue(Files.size(output) <= 8,
                "the limit is a write guard, not a policy checked after the full file exists");
    }

    @Test
    void fileWriterRejectsOutOfOrderTargetSequences() {
        List<ChangeRecord> records = List.of(
                change(Op.INSERT, 2L, Map.of("id", intVal(2)), Map.of("id", intVal(2))),
                change(Op.INSERT, 1L, Map.of("id", intVal(1)), Map.of("id", intVal(1))));

        assertThrows(IllegalArgumentException.class, () -> DeltaParquetWriter.writeDeltaParquet(
                tempDir.resolve("out-of-order.parquet"), "customers", SCHEMA,
                consumer -> records.forEach(consumer), Long.MAX_VALUE, ROW_GROUP_BYTES));
    }

    @Test
    void fileWriterFlushesRowGroupsAtTheConfiguredByteBudget() throws Exception {
        // The streaming writer keeps only the current row group in heap, so that budget — not the
        // parquet-mr implicit ~128 MB — is what bounds a build's memory. Same data, two budgets.
        List<ChangeRecord> records = noisyRecords("customers", 4000);
        Path split = tempDir.resolve("delta-split.parquet");
        Path single = tempDir.resolve("delta-single.parquet");

        DeltaParquetWriter.writeDeltaParquet(split, "customers", NOISY_SCHEMA,
                consumer -> records.forEach(consumer), Long.MAX_VALUE, 64L * 1024);
        DeltaParquetWriter.writeDeltaParquet(single, "customers", NOISY_SCHEMA,
                consumer -> records.forEach(consumer), Long.MAX_VALUE, 128L * 1024 * 1024);

        assertTrue(rowGroupCount(split) > 1, "a 64 KiB budget must flush several row groups");
        assertEquals(1, rowGroupCount(single), "a 128 MiB budget must keep one row group");
    }

    @Test
    void batchWriterFlushesRowGroupsAtTheConfiguredByteBudget() throws Exception {
        // Each open per-table writer of a grouped batch build holds its own row-group buffer, so
        // this is the budget that multiplies by the table count on the completed-batch path.
        List<ChangeRecord> records = noisyRecords("customers", 4000);
        Path split = tempDir.resolve("batch-split.parquet");
        Path single = tempDir.resolve("batch-single.parquet");

        DeltaParquetWriter.writeBatchDeltaParquet(
                Map.of("customers", new DeltaParquetWriter.TableWriteRequest(split, NOISY_SCHEMA)),
                consumer -> records.forEach(consumer), Long.MAX_VALUE, 64L * 1024);
        DeltaParquetWriter.writeBatchDeltaParquet(
                Map.of("customers", new DeltaParquetWriter.TableWriteRequest(single, NOISY_SCHEMA)),
                consumer -> records.forEach(consumer), Long.MAX_VALUE, 128L * 1024 * 1024);

        assertTrue(rowGroupCount(split) > 1, "a 64 KiB budget must flush several row groups");
        assertEquals(1, rowGroupCount(single), "a 128 MiB budget must keep one row group");
    }

    private static final TableSchema NOISY_SCHEMA = new TableSchema(List.of(
            new ColumnDefinition("id", "bigint", false),
            new ColumnDefinition("payload", "varchar(255)", false)),
            List.of("id"), List.of());

    /** Records whose payload does not compress away, so the buffered size actually reaches a budget. */
    private static List<ChangeRecord> noisyRecords(String table, int count) {
        java.util.Random random = new java.util.Random(112L);
        List<ChangeRecord> records = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] noise = new byte[150];
            random.nextBytes(noise);
            records.add(changeForTable(table, Op.INSERT, i + 1L, Map.of("id", intVal(i)),
                    Map.of("id", intVal(i), "payload",
                            strVal(java.util.Base64.getEncoder().encodeToString(noise)))));
        }
        return records;
    }

    private static int rowGroupCount(Path file) throws Exception {
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(new LocalInputFile(file),
                             org.apache.parquet.ParquetReadOptions.builder(new PlainParquetConfiguration())
                                     .build())) {
            return reader.getRowGroups().size();
        }
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
