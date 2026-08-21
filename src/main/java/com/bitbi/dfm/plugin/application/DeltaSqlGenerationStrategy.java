package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.ParquetSchemaMapper;
import com.bitbi.dfm.delta.application.ValueMapper;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import com.bitbi.dfm.site.domain.TableSchema;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQL generation strategy for Delta v2 sites (026-bitbi-delta-sql).
 *
 * <p>Renders a batch's changelog segment records into SQL by mapping each proto
 * {@link ChangeRecord} onto the CDC pipeline's {@link JsonlChangeRecord} (the two carry the same
 * op/key/data semantics) and reusing {@link SqlStatementGenerator}. The per-table baseline filter
 * implements the activation/reinit contract: a record is emitted iff
 * {@code seq > baselineSeq(table)} (missing table → 0, i.e. full retained history streams).</p>
 *
 * <p>{@code FULL_SNAPSHOT} segments are never rendered — emitting them would duplicate the
 * client's entire dataset; the queue service suspends the site's baselines instead.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSqlGenerationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DeltaSqlGenerationStrategy.class);

    static final String MODE_FULL_SNAPSHOT = "FULL_SNAPSHOT";

    private final ChangelogSegmentService changelogSegmentService;
    private final SqlStatementGenerator sqlStatementGenerator;
    private final MeterRegistry meterRegistry;

    public DeltaSqlGenerationStrategy(
            ChangelogSegmentService changelogSegmentService,
            SqlStatementGenerator sqlStatementGenerator,
            MeterRegistry meterRegistry) {
        this.changelogSegmentService = changelogSegmentService;
        this.sqlStatementGenerator = sqlStatementGenerator;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Generates SQL for the given segments (normally one — a batch maps to one segment).
     *
     * @param batchId      source batch (diagnostics)
     * @param siteId       owning site (diagnostics)
     * @param segments     the batch's changelog segments
     * @param tableSchemas declared table schemas of the site (SubmitSchema)
     * @param baselineSeqs per-table baseline seqs; emit iff {@code seq > baselineSeqs.get(table)}
     * @return result with SQL text and statistics, or {@code null} if nothing was generated
     * @throws IOException if reading a segment from S3 fails
     */
    public SqlGenerationResult generate(UUID batchId, UUID siteId, List<ChangelogSegment> segments,
                                        Map<String, TableSchema> tableSchemas,
                                        Map<String, Long> baselineSeqs) throws IOException {
        StringBuilder sqlContent = new StringBuilder();
        int totalInserts = 0;
        int totalUpdates = 0;
        int totalDeletes = 0;
        int segmentsProcessed = 0;

        List<ChangelogSegment> ordered = segments.stream()
                .sorted(Comparator.comparing(ChangelogSegment::getFirstSeq))
                .toList();

        for (ChangelogSegment segment : ordered) {
            if (MODE_FULL_SNAPSHOT.equals(segment.getMode())) {
                log.info("FULL_SNAPSHOT segment is not rendered as SQL: siteId={}, batchId={}, firstSeq={}",
                        siteId, batchId, segment.getFirstSeq());
                continue;
            }

            for (ChangeRecord record : changelogSegmentService.readRecords(segment.getS3Key())) {
                String tableName = record.getTable();
                long seq = record.getSeq();

                if (seq <= baselineSeqs.getOrDefault(tableName, 0L)) {
                    continue;
                }

                TableSchema schema = tableSchemas.get(tableName);
                if (schema == null) {
                    log.warn("No schema found for table '{}', skipping record: batchId={}, seq={}",
                            tableName, batchId, seq);
                    meterRegistry.counter("sql.generation.delta.records.skipped.no_schema").increment();
                    continue;
                }

                JsonlChangeRecord mapped = mapRecord(record, schema, tableName);
                if (mapped == null) {
                    continue;
                }

                sqlContent.append(sqlStatementGenerator.generateFromJsonl(mapped, tableName));

                switch (mapped.op()) {
                    case JsonlChangeRecord.OP_INSERT -> totalInserts++;
                    case JsonlChangeRecord.OP_UPDATE -> totalUpdates++;
                    case JsonlChangeRecord.OP_DELETE -> totalDeletes++;
                    default -> { /* unreachable: mapRecord yields I/U/D only */ }
                }
            }

            segmentsProcessed++;
            meterRegistry.counter("sql.generation.delta.segments.processed").increment();
        }

        if (sqlContent.isEmpty()) {
            return null;
        }

        return new SqlGenerationResult(
                sqlContent.toString(),
                new SqlGenerationStats(totalInserts, totalUpdates, totalDeletes, segmentsProcessed)
        );
    }

    /**
     * Maps a proto change record onto the CDC record shape, filtering unknown data columns
     * against the schema. Returns {@code null} for records that must be skipped.
     */
    private JsonlChangeRecord mapRecord(ChangeRecord record, TableSchema schema, String tableName) {
        int lineNumber = (int) Math.min(record.getSeq(), Integer.MAX_VALUE);
        reportUnrepresentableDecimals(record, tableName);
        String unaddressableKey = unaddressableKeyReason(record, schema);
        if (unaddressableKey != null) {
            // A key column this pipeline cannot represent addresses no row: rendered as SQL the
            // WHERE clause becomes `col = NULL`, which is never true, so an UPDATE or DELETE would
            // be emitted, applied, match nothing, and leave the Bit BI mirror silently diverged
            // (issue #215, review round 1). Skipping is lossy in the same way -- but loudly, and
            // without writing a statement that claims to have done something.
            log.warn("Table '{}' seq {}: {} record skipped -- {}, so its SQL would address no row",
                    tableName, record.getSeq(), record.getOp(), unaddressableKey);
            meterRegistry.counter("sql.generation.delta.records.skipped.unrepresentable_key").increment();
            return null;
        }
        Map<String, Object> key = ValueMapper.toMap(record.getKeyMap());

        return switch (record.getOp()) {
            case INSERT -> {
                // data must be the full row; the wire key may or may not be repeated in data
                Map<String, Object> row = new LinkedHashMap<>(key);
                row.putAll(ValueMapper.toMap(record.getDataMap()));
                yield new JsonlChangeRecord(JsonlChangeRecord.OP_INSERT, null,
                        filterUnknownColumns(row, schema, tableName, lineNumber), lineNumber);
            }
            case UPDATE -> {
                Map<String, Object> data =
                        filterUnknownColumns(ValueMapper.toMap(record.getDataMap()), schema, tableName, lineNumber);
                if (data.isEmpty()) {
                    log.warn("UPDATE with no schema-known columns for table '{}' at seq {} — skipped",
                            tableName, record.getSeq());
                    meterRegistry.counter("sql.generation.delta.records.skipped.empty_update").increment();
                    yield null;
                }
                yield new JsonlChangeRecord(JsonlChangeRecord.OP_UPDATE, key, data, lineNumber);
            }
            case DELETE -> new JsonlChangeRecord(JsonlChangeRecord.OP_DELETE, key, null, lineNumber);
            default -> {
                log.warn("Unknown op '{}' at seq {} for table '{}' — skipped",
                        record.getOp(), record.getSeq(), tableName);
                yield null;
            }
        };
    }

    private Map<String, Object> filterUnknownColumns(Map<String, Object> data, TableSchema schema,
                                                     String tableName, int lineNumber) {
        LinkedHashMap<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (schema.hasColumn(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            } else {
                log.warn("Unknown column '{}' in delta data for table '{}' at seq {} — excluded from SQL",
                        entry.getKey(), tableName, lineNumber);
            }
        }
        return filtered;
    }

    /**
     * Why this record's key addresses no row, or {@code null} when it addresses one.
     *
     * <p>The two reasons have different remedies — a client emitting a value nothing can store, or a
     * client contradicting its own declared schema — which is why the reason travels into the WARN
     * instead of being implied by it. The <em>counter</em> deliberately does not distinguish them:
     * {@code sql.generation.delta.records.skipped.unrepresentable_key} stays one untagged series
     * because the alert is the same either way (a client is sending keys this pipeline cannot
     * address), and tagging a series that already exists untagged breaks the dashboards reading
     * it.</p>
     *
     * @return a phrase naming the column and the reason, or {@code null} if the key is fine
     */
    private String unaddressableKeyReason(ChangeRecord record, TableSchema schema) {
        for (Map.Entry<String, com.bitbi.dfm.delta.grpc.v2.Value> cell : record.getKeyMap().entrySet()) {
            if (ValueMapper.isUnrepresentable(cell.getValue())) {
                return "key column '" + cell.getKey() + "' holds a decimal this pipeline cannot "
                        + "represent (NaN, +/-Infinity or a malformed token)";
            }
            if (isNonFiniteInDecimalColumn(cell.getKey(), cell.getValue(), schema)) {
                return "key column '" + cell.getKey() + "' holds a non-finite value while its declared "
                        + "type materialises as a Parquet DECIMAL, which stores it as NULL";
            }
        }
        return null;
    }

    /**
     * The second way a key cell can address no row, and the one issue #233 had to add rather than
     * inherit: the value is non-finite but arrives on a wire case this pipeline does <em>not</em>
     * lose — a {@code double_value}, or a {@code string_value} spelling {@link ValueMapper}
     * recognises — so the SQL renders it as a quoted {@code 'NaN'} literal, while the column it
     * names is <em>declared</em> {@code numeric(p,s)} and every Parquet artifact therefore writes
     * that key cell NULL. The statement would be valid PostgreSQL addressing a baseline row whose
     * key is NULL: applied, matching nothing, mirror silently diverged.
     *
     * <p>For the {@code double_value} case that silence is what quoting introduced — before #233 the
     * bare {@code NaN} was invalid SQL and Bit BI rejected the file — so the guard ships with it.
     * The {@code string_value} case was silent already, on the same wire-contract violation and with
     * the same outcome, and is guarded here rather than left as a documented twin (review round 4).</p>
     *
     * <p>Note what this does <em>not</em> depend on: an unparseable string such as {@code "abc"} in
     * such a column is NULL in Parquet too, but its SQL fails loudly at apply time
     * ({@code invalid input syntax for type numeric}), so it needs no guard. Only a value both sides
     * consider legal — and only one of them can store — diverges quietly.</p>
     *
     * <p>An {@code INSERT} is skipped by the same rule, deliberately: PostgreSQL would accept
     * {@code VALUES ('NaN', ...)} and create the row, but every later {@code UPDATE} or
     * {@code DELETE} carrying that key is skipped by this guard — it would be a row this stream can
     * create and then never address again.</p>
     *
     * <p>Sending such a column as anything but {@code decimal_value} violates the wire contract and
     * nothing rejects it at ingest. What the <em>data</em> cells of such a row should render as is
     * destination-awareness, which issue #240 owns for both consumers; this guard is only about the
     * key.</p>
     */
    private boolean isNonFiniteInDecimalColumn(String column, com.bitbi.dfm.delta.grpc.v2.Value cell,
                                               TableSchema schema) {
        if (!ValueMapper.isNonFiniteDouble(cell) && !ValueMapper.isNonFiniteString(cell)) {
            return false;
        }
        return schema.findColumn(column)
                .map(definition -> ParquetSchemaMapper.rendersAsParquetDecimal(definition.type()))
                .orElse(false);
    }

    /**
     * A decimal cell this pipeline cannot store under its declared type is rendered as SQL NULL
     * rather than aborting the batch's SQL (issue #215). PostgreSQL {@code numeric} holds
     * {@code NaN} and {@code +/-Infinity}; {@code new BigDecimal} used to throw on all three, and
     * the throw reached the queue rather than this row -- so one such cell cost Bit BI the whole
     * batch.
     *
     * <p>Both maps are walked, not only {@code data}: a DELETE carries no data at all, so scanning
     * one map logged nothing for exactly the records whose key is the interesting part (review
     * round 1). DEBUG per cell because a CDC workload writing these produces one per change; the
     * rate lives on {@code delta.parquet.unrepresentable-decimals} and the Parquet writers log the
     * per-table summary.</p>
     */
    private void reportUnrepresentableDecimals(ChangeRecord record, String tableName) {
        if (!log.isDebugEnabled()) {
            return;
        }
        debugUnrepresentable(record.getKeyMap(), tableName, record.getSeq(), "key");
        debugUnrepresentable(record.getDataMap(), tableName, record.getSeq(), "data");
    }

    private void debugUnrepresentable(Map<String, com.bitbi.dfm.delta.grpc.v2.Value> cells,
                                      String tableName, long seq, String where) {
        for (Map.Entry<String, com.bitbi.dfm.delta.grpc.v2.Value> cell : cells.entrySet()) {
            if (ValueMapper.isNonFiniteDecimal(cell.getValue())) {
                log.debug("Table {} {} column {} at seq {}: non-finite decimal rendered as SQL NULL",
                        tableName, where, cell.getKey(), seq);
            } else if (ValueMapper.isMalformedDecimal(cell.getValue())) {
                log.debug("Table {} {} column {} at seq {}: malformed decimal token rendered as SQL NULL",
                        tableName, where, cell.getKey(), seq);
            }
        }
    }
}
