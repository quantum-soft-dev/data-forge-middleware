package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.bitbi.dfm.util.LogCapture;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #293 — an incremental checkpoint no longer costs a fold of the whole site either.
 *
 * <p>The ticket's second acceptance criterion, on the wired path: a site far larger than its
 * night's work, a budget no fold of that site could fit under, and a build that has to succeed
 * anyway — which it can only do by holding the delta and streaming the site. The budget is the
 * same 64 KiB {@code CheckpointBootstrapStreamingIntegrationTest} pins, and this class declares
 * the identical property set on purpose so the two share one Spring context rather than caching a
 * second one (the suite runs against one shared database, and every extra context is another
 * background worker on it).</p>
 *
 * <p>The first build here is the streamed bootstrap of #292 — it is what produces the seed frame —
 * so what this class adds is everything after it: the merge, its identity with the fold's answer
 * for all three operations, and the hash-partitioned fallback for a delta that does not fit.</p>
 */
@TestPropertySource(properties = {
        "delta.checkpoint.max-fold-bytes=65536",
        "delta.checkpoint.snapshot-writers=8",
        "delta.checkpoint.streaming-bootstrap=true"
})
class CheckpointIncrementalStreamingIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BASELINE_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID DELTA_BATCH = UUID.fromString("0199bab2-ca1c-3d0e-441d-adb776a62579");

    /** Enough rows that a fold of them could not fit the pinned budget by two orders of magnitude. */
    private static final int BASELINE_ROWS = 4_000;

    private static final String TABLE = "merge_customers";

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSchemaService siteSchemaService;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buildsAnIncrementalCheckpointUnderAFoldBudgetTheSiteCouldNotFit() {
        declareSchema();
        long baselineSeq = seedBaseline();

        // The three operations, against a site the budget could never hold: patch row 1, delete
        // row 2, insert a row the baseline never had.
        long deltaSeq = baselineSeq;
        changelogSegmentService.persist(SITE, DELTA_BATCH, "DELTA", deltaSeq + 1, List.of(
                update(TABLE, deltaSeq + 1, 1, "patched"),
                delete(TABLE, deltaSeq + 2, 2),
                insert(TABLE, deltaSeq + 3, BASELINE_ROWS + 1)));
        deltaSeq += 3;

        try (LogCapture log = LogCapture.attachTo(CheckpointService.class)) {
            checkpointService.buildCheckpoint(SITE);

            assertTrue(log.messagesAt(Level.INFO).stream()
                            .anyMatch(line -> line.contains("Merged the checkpoint of site")
                                    && line.contains("no site fold")),
                    "the build must report that it did not fold the site: " + log.messagesAt(Level.INFO));
        }

        Long pointer = jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
        assertEquals(deltaSeq, pointer, "the pointer must reach the delta's last record");

        Checkpoint row = checkpointRepository.findBySiteIdAndTableName(SITE, TABLE).orElseThrow();
        assertNotNull(row.getS3KeyParquet(), "the table must still have a downloadable snapshot");
        assertEquals(BASELINE_ROWS, row.getRowCount(),
                "one row deleted and one inserted leaves the count where it was");

        Map<Long, String> rows = frameRows(deltaSeq);
        assertEquals(BASELINE_ROWS, rows.size());
        assertEquals("patched", rows.get(1L), "the UPDATE merges onto the frame's row");
        assertEquals(null, rows.get(2L), "the DELETE drops the frame's row");
        assertEquals("row-" + (BASELINE_ROWS + 1), rows.get((long) BASELINE_ROWS + 1),
                "the INSERT the frame never had is appended");
        assertEquals("row-3", rows.get(3L), "an untouched row streams through unchanged");
    }

    @Test
    void aDeltaTooLargeForTheBudgetIsMergedInHashPartitionsAndStillPublishesEveryRow() {
        declareSchema();
        long baselineSeq = seedBaseline();

        // A delta that cannot fit 64 KiB on its own — the case the fallback exists for.
        List<ChangeRecord> delta = new ArrayList<>();
        for (int at = 1; at <= 2_000; at++) {
            delta.add(insert(TABLE, baselineSeq + at, BASELINE_ROWS + at));
        }
        changelogSegmentService.persist(SITE, DELTA_BATCH, "DELTA", baselineSeq + 1, delta);
        long deltaSeq = baselineSeq + delta.size();

        try (LogCapture log = LogCapture.attachTo(CheckpointService.class)) {
            checkpointService.buildCheckpoint(SITE);

            assertTrue(log.messagesAt(Level.WARN).stream()
                            .anyMatch(line -> line.contains("hash partitions")),
                    "the fallback must say so in the log: " + log.messagesAt(Level.WARN));
        }

        Long pointer = jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
        assertEquals(deltaSeq, pointer, "a partitioned build still advances the pointer");

        Checkpoint row = checkpointRepository.findBySiteIdAndTableName(SITE, TABLE).orElseThrow();
        assertEquals(BASELINE_ROWS + 2_000, row.getRowCount(),
                "every partition's rows must reach the one frame");
        assertEquals(BASELINE_ROWS + 2_000, frameRows(deltaSeq).size());
    }

    /** Build the site's first checkpoint (the streamed bootstrap of #292) and return its seq. */
    private long seedBaseline() {
        List<ChangeRecord> snapshot = new ArrayList<>(BASELINE_ROWS);
        for (int id = 1; id <= BASELINE_ROWS; id++) {
            snapshot.add(insert(TABLE, id, id));
        }
        changelogSegmentService.persist(SITE, BASELINE_BATCH, "FULL_SNAPSHOT", 1L, snapshot);
        checkpointService.buildCheckpoint(SITE);

        Long pointer = jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
        assertEquals(BASELINE_ROWS, pointer, "the baseline build must have produced the seed frame");
        return BASELINE_ROWS;
    }

    /** Every row of {@code TABLE} in the reload frame at {@code seq}, by id. */
    private Map<Long, String> frameRows(long seq) {
        Map<Long, String> rows = new LinkedHashMap<>();
        AtomicInteger records = new AtomicInteger();
        try (InputStream frame = checkpointStorage.openFrame(SITE, seq)) {
            ChangelogCodec.forEach(frame, record -> {
                assertEquals(Op.INSERT, record.getOp(), "a frame is an all-INSERT changelog");
                records.incrementAndGet();
                rows.put(record.getKeyMap().get("id").getIntValue(),
                        record.getDataMap().get("name").getStringValue());
            });
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(records.get(), rows.size(), "the frame must not repeat a key");
        return rows;
    }

    private void declareSchema() {
        siteSchemaService.upsertSchema(SITE, new SchemaUploadRequestDto(Map.of(
                TABLE, new SchemaUploadRequestDto.TableSchemaDto(
                        List.of(new SchemaUploadRequestDto.ColumnDto("id", "bigint", false),
                                new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true)),
                        List.of("id"), null))));
    }

    private static ChangeRecord insert(String table, long seq, int id) {
        Map<String, Value> key = key(id);
        Map<String, Value> data = new LinkedHashMap<>(key);
        data.put("name", Value.newBuilder().setStringValue("row-" + id).build());
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(Op.INSERT).setSeq(seq).putAllKey(key).putAllData(data).build();
    }

    private static ChangeRecord update(String table, long seq, int id, String name) {
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(Op.UPDATE).setSeq(seq).putAllKey(key(id))
                .putData("name", Value.newBuilder().setStringValue(name).build())
                .build();
    }

    private static ChangeRecord delete(String table, long seq, int id) {
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(Op.DELETE).setSeq(seq).putAllKey(key(id)).build();
    }

    private static Map<String, Value> key(int id) {
        return Map.of("id", Value.newBuilder().setIntValue(id).build());
    }
}
