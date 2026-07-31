package com.bitbi.dfm.delta.presentation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.util.LogCapture;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #83 — the Delta v2 ingestion path is the only way a batch can be created since client API v1 was
 * retired, and it used to carry no logger at all. Every session rejection was invisible: an incident
 * had to be investigated by proving the <em>absence</em> of log lines over 22 hours.
 *
 * <p>These tests pin the observability contract itself: a session leaves a trace when it opens,
 * seals, and commits; every rejection names its {@code ErrorCode} and {@code RecoveryAction} at
 * WARN; an unexpected fault is logged with its stack trace; and no way of ending a session — commit,
 * transport drop, or half-close — leaves nothing behind. And the whole thing stays <em>bounded</em>:
 * per session and per seal, never per record.</p>
 */
class DeltaIngestionSessionLoggingContractTest extends DeltaIngestionContractTestSupport {

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
    void openingAndCommittingASessionIsTraceable() throws Exception {
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);

        runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change(1L));
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(1L)
                    .putPerTable("t", TableStats.newBuilder().setInserts(1).build())
                    .build()).build());
        });

        List<String> info = capture.messagesAt(Level.INFO);
        assertTrue(info.stream().anyMatch(line ->
                        line.contains("session start") && line.contains(SITE.toString())
                                && line.contains("FULL_SNAPSHOT") && line.contains("firstSeq=1")),
                "SessionStart must record the site, mode and first_seq it was asked for: " + info);
        assertTrue(info.stream().anyMatch(line ->
                        line.contains("session opened") && line.contains(batchId.toString())),
                "the opened session must name the batch it created: " + info);
        assertTrue(info.stream().anyMatch(line ->
                        line.contains("session committed") && line.contains("committedSeq=1")),
                "the commit must record the sequence it committed: " + info);
    }

    @Test
    void everyContinuousSealIsTraceable() throws Exception {
        openableBatch(UUID.randomUUID());

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 200; seq++) {
                req.onNext(change(seq));
            }
        });

        List<String> seals = capture.messagesContaining(Level.INFO, "segment sealed");
        assertEquals(2, seals.size(),
                "one line per seal at the 100-record threshold: " + capture.messagesAt(Level.INFO));
        assertTrue(seals.getFirst().contains("records=100"), "a seal must record its size: " + seals.getFirst());
        assertTrue(seals.getFirst().contains(SEGMENT_KEY),
                "a seal must name the segment it wrote: " + seals.getFirst());
    }

    @Test
    void everyRejectionNamesItsCodeAndRecoveryActionAtWarn() throws Exception {
        when(batchLifecycle.startBatch(eq(ACCOUNT), eq(SITE), any()))
                .thenThrow(new BatchLifecycleService.SiteInactiveException("Cannot start batch for inactive site."));

        runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        List<String> warnings = capture.messagesAt(Level.WARN);
        assertEquals(1, warnings.size(), "a rejected session must leave exactly one WARN: " + warnings);
        assertTrue(warnings.getFirst().contains("SITE_INACTIVE"), "the WARN must name the code: " + warnings);
        assertTrue(warnings.getFirst().contains("PROCEED"), "the WARN must name the action: " + warnings);
        assertTrue(warnings.getFirst().contains(SITE.toString()), "the WARN must name the site: " + warnings);
    }

    @Test
    void anUnexpectedFaultIsLoggedWithItsStackTraceInsteadOfVanishingIntoStatusInternal() throws Exception {
        when(batchLifecycle.startBatch(eq(ACCOUNT), eq(SITE), any()))
                .thenThrow(new IllegalStateException("site row vanished"));

        runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        List<ILoggingEvent> errors = capture.eventsAt(Level.ERROR);
        assertEquals(1, errors.size(), "an unhandled fault must leave exactly one ERROR");
        assertTrue(errors.getFirst().getFormattedMessage().contains(SITE.toString()),
                "the ERROR must name the site: " + errors.getFirst().getFormattedMessage());
        assertNotNull(errors.getFirst().getThrowableProxy(),
                "a Status.INTERNAL with no stack trace is what made #83 uninvestigable");
    }

    /**
     * The sibling of the fault above, on the other {@code catch (RuntimeException)}: a CONTINUOUS
     * session half-closes without {@code SessionEnd}, so its final commit runs from
     * {@code onCompleted()}. A failure there also closes the call with a bare {@code Status.INTERNAL}
     * and must not be the one fault that still leaves no trace.
     */
    @Test
    void aFailedFinalCommitOnAContinuousCloseIsAlsoLoggedWithItsStackTrace() throws Exception {
        openableBatch(UUID.randomUUID());
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any()))
                .thenThrow(new IllegalStateException("segment write failed"));

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            req.onNext(change(1L));
        });

        List<ILoggingEvent> errors = capture.eventsAt(Level.ERROR);
        assertEquals(1, errors.size(), "a failed final commit must leave exactly one ERROR");
        assertTrue(errors.getFirst().getFormattedMessage().contains(SITE.toString()),
                "the ERROR must name the site: " + errors.getFirst().getFormattedMessage());
        assertNotNull(errors.getFirst().getThrowableProxy(),
                "Status.INTERNAL carries no stack trace, so the log line must");
    }

    @Test
    void loggingIsBoundedPerSessionNotPerRecord() throws Exception {
        openableBatch(UUID.randomUUID());

        runSession(req -> {
            req.onNext(start(SessionMode.DELTA, 1L));
            for (long seq = 1; seq <= 500; seq++) {
                req.onNext(change(seq));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(500L)
                    .putPerTable("t", TableStats.newBuilder().setInserts(500).build())
                    .build()).build());
        });

        assertTrue(capture.events().size() <= 5,
                "500 records must not produce per-record logging, got " + capture.events().size()
                        + " lines: " + capture.messagesAt(Level.INFO));
    }

    /**
     * A transport drop is how a session most often ends abnormally, and the branches under
     * {@code onError} record only its <em>outcome</em>: a cancel, a deadline, a TLS reset and an
     * oversized message all produce the same "staged for resume" line. The cause has to be logged
     * too, or the drop is the one ending the observability tables cannot explain.
     */
    @Test
    void aTransportDropRecordsWhyTheStreamBroke() throws Exception {
        openableBatch(UUID.randomUUID());

        dropSession(req -> {
            req.onNext(start(SessionMode.DELTA, 1L));
            req.onNext(change(1L));
        });

        List<String> drops = capture.messagesContaining(Level.INFO, "transport drop");
        assertEquals(1, drops.size(),
                "a dropped session must leave exactly one line: " + capture.messagesAt(Level.INFO));
        assertTrue(drops.getFirst().contains(SITE.toString()), "the drop must name the site: " + drops);
        assertTrue(drops.getFirst().contains("status=CANCELLED"),
                "the drop must name the gRPC status that broke the stream: " + drops);
    }

    /**
     * The case with no other trace at all: {@code stageForResume()} no-ops when no session was ever
     * opened, so a client that connects, sends nothing and drops used to be indistinguishable from
     * one that never called.
     */
    @Test
    void aDropBeforeAnySessionOpensStillLeavesATrace() throws Exception {
        dropSession(req -> {
        });

        assertFalse(capture.messagesContaining(Level.INFO, "transport drop").isEmpty(),
                "a connect-and-drop must still be traceable: " + capture.events());
        verify(batchLifecycle, never()).startBatch(any(), any(), any());
    }

    /**
     * The half-close twin of the drop above, and the last silent ending: a client that streams
     * change records without a {@code SessionStart} the server accepted has every record discarded
     * by {@code onChange}, then half-closes cleanly. No {@code ServerError}, no batch, and — before
     * this — no log line either, so both sides looked healthy while the data went nowhere. One line
     * per session, not per discarded record.
     */
    @Test
    void changeRecordsWithNoOpenSessionAreReportedOnceInsteadOfDiscardedSilently() throws Exception {
        runSession(req -> {
            for (long seq = 1; seq <= 50; seq++) {
                req.onNext(change(seq));
            }
        });

        List<String> warnings = capture.messagesAt(Level.WARN);
        assertEquals(1, warnings.size(),
                "50 discarded records must produce one line, not 50 and not none: " + warnings);
        assertTrue(warnings.getFirst().contains("no open session"),
                "the WARN must say why the records were dropped: " + warnings);
        assertTrue(warnings.getFirst().contains(SITE.toString()), "the WARN must name the site: " + warnings);
        verify(batchLifecycle, never()).startBatch(any(), any(), any());
    }

    private void runSession(Consumer<StreamObserver<ClientEvent>> client) throws InterruptedException {
        exchange(client, StreamObserver::onCompleted);
    }

    /** Like {@link #runSession}, but the client breaks the transport instead of half-closing. */
    private void dropSession(Consumer<StreamObserver<ClientEvent>> client) throws InterruptedException {
        exchange(client, request -> request.onError(new RuntimeException("transport drop")));
    }

    private void exchange(Consumer<StreamObserver<ClientEvent>> client,
                          Consumer<StreamObserver<ClientEvent>> terminator) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> request =
                asyncStub.streamChanges(collect(new CopyOnWriteArrayList<>(), done));
        client.accept(request);
        terminator.accept(request);
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not terminate");
    }
}
