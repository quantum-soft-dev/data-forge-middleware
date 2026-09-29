package com.bitbi.dfm.delta.presentation;

import ch.qos.logback.classic.Level;
import com.bitbi.dfm.delta.application.ChangelogContentHash;
import com.bitbi.dfm.delta.application.RowHash;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.util.LogCapture;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Issue #369 — what ingestion does with {@code ChangeRecord.row_hash}: an older client that sends
 * none is accepted exactly as before, a 32-byte hash is staged untouched, and a value of any other
 * length is dropped from the record with one WARN per session rather than failing it — there is no
 * {@code ErrorCode} for it. None of it touches the session's {@code content_hash}.
 */
class DeltaIngestionRowHashContractTest extends DeltaIngestionContractTestSupport {

    private LogCapture capture;

    @BeforeEach
    void captureIngestionLog() {
        capture = LogCapture.attachTo(DeltaIngestionService.class);
    }

    @AfterEach
    void releaseIngestionLog() {
        capture.close();
    }

    @Test
    void anOlderClientSendingNoRowHashIsAcceptedAsBefore() throws Exception {
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);
        List<ChangeRecord> sent = List.of(record(1L, ByteString.EMPTY), record(2L, ByteString.EMPTY));

        List<ServerEvent> events = runSession(sent);

        assertCommitted(events, 2L);
        assertEquals(sent, staged(batchId), "records without a hash are staged byte for byte");
        assertTrue(capture.messagesContaining(Level.WARN, "row_hash").isEmpty());
    }

    @Test
    void aThirtyTwoByteRowHashIsStagedAsSent() throws Exception {
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);
        List<ChangeRecord> sent = List.of(record(1L, hash(1)), record(2L, hash(2)));

        List<ServerEvent> events = runSession(sent);

        assertCommitted(events, 2L);
        assertEquals(sent, staged(batchId), "the hash reaches the segment untouched");
        assertTrue(capture.messagesContaining(Level.WARN, "row_hash").isEmpty());
    }

    @Test
    void aRowHashOfAnyOtherLengthIsDroppedTheSessionCommitsAndOneWarnIsLogged() throws Exception {
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);
        List<ChangeRecord> sent = List.of(
                record(1L, ByteString.copyFrom(new byte[31])),
                record(2L, hash(2)),
                record(3L, ByteString.copyFrom(new byte[64])),
                record(4L, ByteString.copyFrom(new byte[1])));

        List<ServerEvent> events = runSession(sent);

        assertCommitted(events, 4L);
        List<ChangeRecord> staged = staged(batchId);
        assertEquals(4, staged.size());
        assertTrue(staged.get(0).getRowHash().isEmpty(), "a 31-byte value is not a row hash");
        assertEquals(hash(2), staged.get(1).getRowHash(), "a valid neighbour keeps its hash");
        assertTrue(staged.get(2).getRowHash().isEmpty());
        assertTrue(staged.get(3).getRowHash().isEmpty());
        List<String> warnings = capture.messagesContaining(Level.WARN, "row_hash");
        assertEquals(1, warnings.size(), "one WARN per session, not one per record: " + warnings);
        assertTrue(warnings.getFirst().contains(SITE.toString()), "the WARN names the site: " + warnings);
        assertTrue(warnings.getFirst().contains("31"), "the WARN names the length it refused: " + warnings);
    }

    /** Runs one FULL_SNAPSHOT session whose {@code content_hash} is computed without any row hash. */
    private List<ServerEvent> runSession(List<ChangeRecord> records) throws InterruptedException {
        List<ChangeRecord> withoutHashes = new ArrayList<>();
        records.forEach(record -> withoutHashes.add(record.toBuilder().clearRowHash().build()));
        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> request = asyncStub.streamChanges(collect(events, done));
        request.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
        records.forEach(record -> request.onNext(ClientEvent.newBuilder().setChange(record).build()));
        request.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                .setLastSeq(records.size())
                .putPerTable("t", TableStats.newBuilder().setInserts(records.size()).build())
                .setContentHash(ChangelogContentHash.compute(withoutHashes))
                .build()).build());
        request.onCompleted();
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not terminate");
        return events;
    }

    private static void assertCommitted(List<ServerEvent> events, long seq) {
        ServerEvent last = events.get(events.size() - 1);
        assertTrue(last.hasCommitted(), "the session commits — the content_hash leaves row_hash out: " + events);
        assertEquals(seq, last.getCommitted().getCommittedSeq());
    }

    private List<ChangeRecord> staged(UUID batchId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(changelogSegmentService).prepare(eq(SITE), eq(batchId), eq("FULL_SNAPSHOT"), anyLong(),
                records.capture());
        return records.getValue();
    }

    private static ChangeRecord record(long seq, ByteString rowHash) {
        return ChangeRecord.newBuilder()
                .setTable("t").setOp(Op.INSERT).setSeq(seq)
                .putKey("id", Value.newBuilder().setIntValue(seq).build())
                .putData("id", Value.newBuilder().setIntValue(seq).build())
                .setRowHash(rowHash)
                .build();
    }

    private static ByteString hash(int fill) {
        byte[] bytes = new byte[RowHash.LENGTH];
        java.util.Arrays.fill(bytes, (byte) fill);
        return ByteString.copyFrom(bytes);
    }
}
