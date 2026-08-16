package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Issue #147 — the ingestion commit must not perform S3 I/O while it holds database locks.
 *
 * <p>The unit tests pin the call order; only the wired application can show that the
 * {@code PutObject} genuinely runs with no transaction open, because the transaction is started by
 * a Spring proxy that unit tests do not have. The spy records
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} at the moment of the upload
 * and then performs the real one.</p>
 */
class DeltaSessionCommitTransactionIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    /** store-01's seeded IN_PROGRESS batch — commit() completes it. */
    private static final UUID IN_PROGRESS_BATCH = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903");
    /** Already COMPLETED, so completing it again fails the commit transaction deterministically. */
    private static final UUID COMPLETED_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @MockitoSpyBean
    private S3ChangelogSegmentStorage segmentStorage;

    @Autowired
    private DeltaSessionCommitService commitService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** One observed {@code PutObject}: was a transaction open, and what key did it land on. */
    private record Upload(boolean insideTransaction, String s3Key) {
    }

    private final List<Upload> uploads = new ArrayList<>();

    @BeforeEach
    void recordTransactionStateAtUpload() {
        uploads.clear();
        doAnswer(invocation -> {
            boolean insideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            String s3Key = (String) invocation.callRealMethod();
            uploads.add(new Upload(insideTransaction, s3Key));
            return s3Key;
        }).when(segmentStorage).uploadSegment(any(), any(), any());
    }

    private List<Boolean> insideTransaction() {
        return uploads.stream().map(Upload::insideTransaction).toList();
    }

    @Test
    void theSegmentObjectIsUploadedWithNoTransactionOpen() {
        commitService.commit(SITE, IN_PROGRESS_BATCH, "DELTA", 1L, 2L,
                List.of(insert(1L, 1L, "Ann"), insert(2L, 2L, "Bob")));

        assertEquals(List.of(false), insideTransaction(),
                "the segment PutObject must not run inside the commit transaction");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isPresent(),
                "the commit itself still wrote the segment row");
    }

    @Test
    void aFullSnapshotTailIsUploadedBeforeTheRebaselineLocksTheSiteRow() {
        // The case the ticket was raised for: DeltaRebaselineService.reset takes the
        // site_sync_state row lock as the commit transaction's first statement (issue #142). The
        // tail's upload has to be finished before that, or the per-site mutex spans a network call.
        changelogSegmentService.persist(SITE, COMPLETED_BATCH, "FULL_SNAPSHOT", 1L,
                List.of(insert(1L, 1L, "Ann")));
        uploads.clear();

        commitService.commit(SITE, IN_PROGRESS_BATCH, "FULL_SNAPSHOT", 10L, 10L,
                List.of(insert(10L, 2L, "Bob")), true);

        assertEquals(List.of(false), insideTransaction(),
                "the snapshot tail must be uploaded before the reset takes the row lock");
        // The re-baseline still did its work, in the order #142 requires.
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty(),
                "the previous baseline's segment is gone");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 10L).isPresent(),
                "the snapshot tail replaced it");
        assertEquals(1L, jdbc.queryForMap("SELECT * FROM site_sync_state WHERE site_id = ?", SITE)
                        .get("baseline_epoch"),
                "the baseline epoch moved, so a build that overlapped is still refused");
    }

    @Test
    void aRolledBackCommitLeavesTheUploadedObjectAsAnUnreferencedOrphan() {
        // Uploading first means an object can outlive a failed transaction. That is not new: the
        // upload was never compensated on rollback when it sat inside the transaction either, and
        // the key carries a freshly minted segment id, so nothing can reach the object without the
        // row. Pinned so the trade-off stays a decision rather than a surprise.
        assertThrows(RuntimeException.class, () -> commitService.commit(SITE, COMPLETED_BATCH, "DELTA",
                100L, 100L, List.of(insert(100L, 3L, "Cy"))));

        assertEquals(List.of(false), insideTransaction());
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 100L).isEmpty(),
                "the failed transaction wrote no segment row");
        assertTrue(segmentStorage.exists(uploads.getFirst().s3Key()),
                "the uploaded object survives, unreferenced");
    }

    private static ChangeRecord insert(long seq, long id, String name) {
        Map<String, Value> key = new LinkedHashMap<>();
        key.put("id", Value.newBuilder().setIntValue(id).build());
        Map<String, Value> data = new LinkedHashMap<>(key);
        data.put("name", Value.newBuilder().setStringValue(name).build());
        return ChangeRecord.newBuilder()
                .setTable("customers")
                .setOp(Op.INSERT)
                .setSeq(seq)
                .putAllKey(key)
                .putAllData(data)
                .build();
    }
}
