package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.bitbi.dfm.util.LogCapture;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #292 — a site's first checkpoint no longer costs a fold of the whole site.
 *
 * <p>The ticket's second acceptance criterion asks for the proof on a <b>synthetic site of
 * comparable shape</b> rather than only in production: many tables, many rows, one
 * {@code FULL_SNAPSHOT} session, no seed frame. The heap claim is stated the only way a test can
 * state it deterministically — {@code delta.checkpoint.max-fold-bytes} is pinned to a value no fold
 * of this site could ever fit under, so the general path would abort with {@code fold_too_large}
 * before it wrote anything. The build has to succeed anyway, which it can only do by not folding.</p>
 *
 * <p>The pass count is the second measurement: {@code 1 + ceil(tables / snapshot-writers)} when any
 * table declares a decimal column and {@code ceil(tables / snapshot-writers)} otherwise — a function
 * of the table count and the configured writer count, never of the row count. It is read off the
 * line the build logs, which is also where an operator reads it.</p>
 */
@TestPropertySource(properties = {
        // Two orders of magnitude below what a fold of 40 x 250 rows costs, so the general path
        // could not possibly complete under it. See the class Javadoc.
        "delta.checkpoint.max-fold-bytes=65536",
        "delta.checkpoint.snapshot-writers=8",
        "delta.checkpoint.streaming-bootstrap=true"
})
class CheckpointBootstrapStreamingIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /** Enough tables that the writers group, few enough that the suite stays quick. */
    private static final int TABLES = 40;
    private static final int ROWS_PER_TABLE = 250;
    private static final int SNAPSHOT_WRITERS = 8;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSchemaService siteSchemaService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buildsAManyTableFirstCheckpointUnderAFoldBudgetNoFoldCouldFit() {
        declareSchemas();
        seedSnapshotSegments();

        checkpointService.buildCheckpoint(SITE);

        List<Checkpoint> rows = checkpointRepository.findBySiteId(SITE).stream()
                .filter(row -> row.getTableName().startsWith("bootstrap_t"))
                .toList();
        assertEquals(TABLES, rows.size(), "every table of the snapshot must have a checkpoint row");
        for (Checkpoint row : rows) {
            assertNotNull(row.getS3KeyParquet(),
                    "table " + row.getTableName() + " must have a downloadable snapshot");
            assertEquals(ROWS_PER_TABLE, row.getRowCount(),
                    "table " + row.getTableName() + " must record every row it was sent");
        }

        Long pointer = jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
        assertEquals((long) TABLES * ROWS_PER_TABLE, pointer,
                "the pointer must reach the last record of the snapshot session");
    }

    /**
     * The pass count is what makes this bounded, so it is asserted rather than described. Every
     * declared table here carries a {@code numeric} column, so the envelope pass is made:
     * {@code 1 + ceil(40 / 8) = 6}.
     */
    @Test
    void makesAPassCountSetByTheTableCountRatherThanTheRowCount() {
        declareSchemas();
        seedSnapshotSegments();

        try (LogCapture log = LogCapture.attachTo(CheckpointService.class)) {
            checkpointService.buildCheckpoint(SITE);

            List<String> lines = log.messagesAt(Level.INFO);
            String line = lines.stream()
                    .filter(message -> message.contains("Streamed the first checkpoint"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the streamed build must report its passes: " + lines));
            int expected = 1 + (TABLES + SNAPSHOT_WRITERS - 1) / SNAPSHOT_WRITERS;
            assertTrue(line.contains(expected + " pass(es) over the local frame"),
                    "expected " + expected + " passes, got: " + line);
            assertTrue(line.contains((long) TABLES * ROWS_PER_TABLE + " record(s)"),
                    "the line must carry the record count it did not have to fold: " + line);
        }
    }

    private void declareSchemas() {
        Map<String, SchemaUploadRequestDto.TableSchemaDto> tables = new LinkedHashMap<>();
        for (int table = 0; table < TABLES; table++) {
            tables.put("bootstrap_t" + table, new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(new SchemaUploadRequestDto.ColumnDto("id", "bigint", false),
                            new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true),
                            // A declared decimal, so the envelope pass is exercised (and counted).
                            new SchemaUploadRequestDto.ColumnDto("amount", "numeric(12,2)", true)),
                    List.of("id"), null));
        }
        siteSchemaService.upsertSchema(SITE, new SchemaUploadRequestDto(tables));
    }

    /**
     * One {@code FULL_SNAPSHOT} session, sealed into several segments as a real client's would be,
     * with the tables interleaved — which is what the streamed frame has to cope with and the fold
     * never did.
     */
    private void seedSnapshotSegments() {
        long seq = 0;
        List<ChangeRecord> pending = new ArrayList<>();
        long firstSeq = 1;
        for (int row = 0; row < ROWS_PER_TABLE; row++) {
            for (int table = 0; table < TABLES; table++) {
                pending.add(insert("bootstrap_t" + table, ++seq, row));
            }
            if (pending.size() >= 4000) {
                changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", firstSeq, List.copyOf(pending));
                firstSeq = seq + 1;
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", firstSeq, List.copyOf(pending));
        }
    }

    private static ChangeRecord insert(String table, long seq, int id) {
        Map<String, Value> key = Map.of("id", Value.newBuilder().setIntValue(id).build());
        Map<String, Value> data = new LinkedHashMap<>(key);
        data.put("name", Value.newBuilder().setStringValue("row-" + id).build());
        data.put("amount", Value.newBuilder().setDecimalValue(id + ".50").build());
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(Op.INSERT).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }
}
