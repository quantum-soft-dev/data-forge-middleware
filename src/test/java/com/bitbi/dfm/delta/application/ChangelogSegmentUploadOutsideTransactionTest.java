package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #147 — the segment {@code PutObject} is a network call and must not run while a database
 * transaction (and the row locks it has taken) is open. The service therefore splits into an
 * upload half ({@code prepare}) and a row half ({@code persistPrepared}), and the upload half
 * refuses to run inside a transaction rather than silently reintroducing the long hold.
 */
class ChangelogSegmentUploadOutsideTransactionTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();

    private final S3ChangelogSegmentStorage storage = mock(S3ChangelogSegmentStorage.class);
    private final ChangelogSegmentRepository repository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService service = new ChangelogSegmentService(storage, repository);

    @Test
    void prepareUploadsTheObjectAndWritesNoRow() {
        when(storage.uploadSegment(eq(SITE), any(UUID.class), any()))
                .thenAnswer(inv -> "delta/" + SITE + "/segments/" + inv.getArgument(1) + ".pb.gz");

        PreparedSegment prepared = service.prepare(SITE, BATCH, "DELTA", 1L,
                List.of(rec("orders", Op.INSERT, 1L), rec("orders", Op.DELETE, 2L)));

        assertEquals(SITE, prepared.siteId());
        assertEquals(1L, prepared.firstSeq());
        assertEquals(2L, prepared.lastSeq());
        assertEquals(2, prepared.recordCount());
        assertEquals("delta/" + SITE + "/segments/" + prepared.segmentId() + ".pb.gz", prepared.s3Key());
        assertEquals(new TableChangeStats(1, 0, 1), prepared.stats().get("orders"));
        assertTrue(prepared.contentHash().matches("[0-9a-f]{64}"), "content hash is sha-256 hex");
        // The row half has not run: nothing may reach the database before the object exists.
        verifyNoInteractions(repository);
    }

    @Test
    void persistPreparedWritesTheRowFromTheUploadAndTouchesNoStorage() {
        PreparedSegment prepared = new PreparedSegment(UUID.randomUUID(), SITE, BATCH, "DELTA", 5L, 7L, 3,
                "cafebabe", "delta/site/segments/x.pb.gz",
                java.util.Map.of("orders", new TableChangeStats(3, 0, 0)));
        ArgumentCaptor<ChangelogSegment> captor = ArgumentCaptor.forClass(ChangelogSegment.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ChangelogSegment saved = service.persistPrepared(prepared);

        assertEquals(prepared.segmentId(), saved.getId());
        assertEquals(prepared.s3Key(), saved.getS3Key());
        assertEquals(prepared.contentHash(), saved.getContentHash());
        assertEquals(BATCH, saved.getBatchId());
        assertEquals(5L, saved.getFirstSeq());
        assertEquals(7L, saved.getLastSeq());
        assertEquals(new TableChangeStats(3, 0, 0), captor.getValue().getStats().get("orders"));
        verifyNoInteractions(storage);
    }

    @Test
    void persistPreparedProvisionalMarksTheRowInvisible() {
        PreparedSegment prepared = new PreparedSegment(UUID.randomUUID(), SITE, BATCH, "FULL_SNAPSHOT",
                5L, 5L, 1, "cafebabe", "delta/site/segments/x.pb.gz", java.util.Map.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.persistPreparedProvisional(prepared).isProvisional());
    }

    @Test
    void prepareRefusesToUploadInsideATransaction() {
        // The whole point of the split: an ambient transaction would hold its locks (on the
        // FULL_SNAPSHOT path, the site_sync_state row DeltaRebaselineService.reset takes) for the
        // duration of the PutObject. Fail loudly instead of regressing quietly.
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> service.prepare(SITE, BATCH, "DELTA", 1L, List.of(rec("orders", Op.INSERT, 1L))));
            assertTrue(thrown.getMessage().contains("transaction"), thrown.getMessage());
            verifyNoInteractions(storage);
            verifyNoInteractions(repository);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void persistStillComposesUploadThenRowForCallersOutsideATransaction() {
        when(storage.uploadSegment(eq(SITE), any(UUID.class), any()))
                .thenAnswer(inv -> "delta/" + SITE + "/segments/" + inv.getArgument(1) + ".pb.gz");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChangelogSegment saved = service.persist(SITE, BATCH, "DELTA", 1L,
                List.of(rec("orders", Op.INSERT, 1L)));

        assertEquals("delta/" + SITE + "/segments/" + saved.getId() + ".pb.gz", saved.getS3Key());
    }

    private static ChangeRecord rec(String table, Op op, long seq) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq).build();
    }
}
