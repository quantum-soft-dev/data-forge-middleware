package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plugin-SQL requeue must skip FULL_SNAPSHOT segments (issue #89, drive-by fix).
 *
 * <p>{@code DeltaSqlQueueService.processNextPending} routes every FULL_SNAPSHOT segment it claims to
 * {@code suspendBaselines}, which parks the site's baselines at {@code Long.MAX_VALUE}. So a
 * recapture that re-enqueues snapshot segments has its freshly captured baselines suspended again
 * the moment the sweep runs — which broke manual reinit after a re-baseline just as much as it would
 * break the automatic recapture after a wipe. Snapshot segments never render as SQL anyway.</p>
 */
@DisplayName("Changelog segment requeue")
class ChangelogSegmentRequeueContractTest extends BaseIntegrationTest {

    /** store-01.example.com. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** A COMPLETED batch of that site, seeded by test-data.sql. */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedSegments() {
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE);
        insertSegment(1L, 10L, "DELTA");
        insertSegment(11L, 20L, "FULL_SNAPSHOT");
        insertSegment(21L, 30L, "DELTA");
    }

    private void insertSegment(long firstSeq, long lastSeq, String mode) {
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq, record_count,
                                                content_hash, s3_key, mode, provisional, plugin_sql_at)
                VALUES (?, ?, ?, ?, ?, 10, 'hash', ?, ?, FALSE, CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, UUID.randomUUID(), SITE, BATCH, firstSeq, lastSeq,
                "segments/" + firstSeq + ".pb.gz", mode);
    }

    @Test
    @DisplayName("re-enqueues delta segments but leaves FULL_SNAPSHOT ones processed")
    void shouldExcludeFullSnapshotSegments() {
        int requeued = segmentRepository.clearPluginSqlBySiteId(SITE);

        assertThat(requeued).isEqualTo(2);
        Long pendingSnapshots = jdbc.queryForObject("""
                SELECT COUNT(*) FROM changelog_segments
                WHERE site_id = ? AND mode = 'FULL_SNAPSHOT' AND plugin_sql_at IS NULL
                """, Long.class, SITE);
        assertThat(pendingSnapshots).isZero();
    }
}
