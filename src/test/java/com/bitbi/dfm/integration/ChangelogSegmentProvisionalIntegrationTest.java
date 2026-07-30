package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 033/T02 — a provisional segment is durable but invisible.
 *
 * <p>A re-baseline larger than the session buffer seals bounded segments as it streams. Those
 * segments must not reach the checkpoint fold, the delta-Parquet egress queue or the Bit BI SQL
 * queue until {@code SessionEnd} flips them, or a half-streamed snapshot would be folded on top of
 * the baseline it is supposed to replace and published downstream.</p>
 */
class ChangelogSegmentProvisionalIntegrationTest extends BaseIntegrationTest {

    /** store-01.example.com — test-data.sql clears its segments before every test method. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ChangelogSegmentService service;

    @Autowired
    private ChangelogSegmentRepository repository;

    private List<ChangeRecord> records(long seq) {
        return List.of(ChangeRecord.newBuilder().setTable("customers").setOp(Op.INSERT).setSeq(seq).build());
    }

    @Test
    void provisionalSegmentIsHiddenFromFoldAndBothQueues() {
        ChangelogSegment provisional = service.persistProvisional(SITE, BATCH, "FULL_SNAPSHOT", 1L, records(1L));

        assertTrue(provisional.isProvisional());

        assertFalse(repository.findBySiteIdOrderByFirstSeq(SITE).stream()
                        .anyMatch(s -> s.getId().equals(provisional.getId())),
                "the checkpoint fold must not see a snapshot that is still streaming");
        assertFalse(repository.findNextPendingEgress(50).stream()
                        .anyMatch(s -> s.getId().equals(provisional.getId())),
                "a provisional segment must not be published as delta Parquet");
        assertFalse(repository.findNextPendingPluginSql(50).stream()
                        .anyMatch(s -> s.getId().equals(provisional.getId())),
                "a provisional segment must not enter the Bit BI SQL queue");
    }

    @Test
    void flippingMakesTheWholeBatchVisibleAtOnce() {
        ChangelogSegment first = service.persistProvisional(SITE, BATCH, "FULL_SNAPSHOT", 10L, records(10L));
        ChangelogSegment second = service.persistProvisional(SITE, BATCH, "FULL_SNAPSHOT", 11L, records(11L));

        int flipped = repository.flipProvisionalByBatchId(BATCH);
        assertEquals(2, flipped);

        List<UUID> visible = repository.findBySiteIdOrderByFirstSeq(SITE).stream()
                .map(ChangelogSegment::getId).toList();
        assertTrue(visible.contains(first.getId()));
        assertTrue(visible.contains(second.getId()));

        assertTrue(repository.findNextPendingEgress(50).stream()
                        .anyMatch(s -> s.getId().equals(first.getId())),
                "once flipped the snapshot enters the egress queue at its head");
    }

    @Test
    void findProvisionalReturnsOnlyUnflippedSegmentsOfTheSite() {
        ChangelogSegment committed = service.persist(SITE, BATCH, "DELTA", 20L, records(20L));
        ChangelogSegment pending = service.persistProvisional(SITE, BATCH, "FULL_SNAPSHOT", 21L, records(21L));

        List<UUID> found = repository.findProvisionalBySiteId(SITE).stream()
                .map(ChangelogSegment::getId).toList();

        assertTrue(found.contains(pending.getId()));
        assertFalse(found.contains(committed.getId()));
    }
}
