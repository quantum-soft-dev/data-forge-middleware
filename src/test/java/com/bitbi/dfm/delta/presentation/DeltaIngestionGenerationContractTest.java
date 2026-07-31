package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.grpc.v2.ClientEvent;
import com.bitbi.dfm.delta.grpc.v2.ErrorCode;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.RecoveryAction;
import com.bitbi.dfm.delta.grpc.v2.ServerEvent;
import com.bitbi.dfm.delta.grpc.v2.SessionMode;
import com.bitbi.dfm.delta.grpc.v2.SessionStart;
import com.bitbi.dfm.delta.grpc.v2.SyncStateRequest;
import com.bitbi.dfm.delta.grpc.v2.SyncStateResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The generation (epoch) wire contract (035 — site history wipe, issue #89).
 *
 * <p>The client learns that a wipe happened only through {@code generation}: it is emitted on every
 * surface the client reads before it decides what to send, and a session that carries a stale epoch
 * is refused rather than allowed to graft pre-wipe sequence numbers onto a fresh site.</p>
 */
class DeltaIngestionGenerationContractTest extends DeltaIngestionContractTestSupport {

    /** A site that has been wiped twice and re-synced; generation is not derivable from the seqs. */
    private SiteSyncState atGeneration(long generation) {
        SiteSyncState state = SiteSyncState.initial(SITE);
        for (long i = 0; i < generation; i++) {
            state.resetForWipe();
        }
        // Un-park the post-wipe rebaseline request so the session tests exercise the epoch guard
        // rather than tripping over NEED_REBASELINE first.
        state.resetForRebaseline(120L);
        state.advanceWatermark(120L);
        return state;
    }

    private static ClientEvent startAtGeneration(SessionMode mode, long firstSeq, long generation) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder()
                        .setMode(mode)
                        .setFirstSeq(firstSeq)
                        .setGeneration(generation)
                        .build())
                .build();
    }

    @Test
    @DisplayName("GetSyncState reports the site's generation")
    void shouldEmitGenerationInSyncState() {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(3)));

        SyncStateResponse response = blockingStub.getSyncState(SyncStateRequest.newBuilder().build());

        assertEquals(3L, response.getGeneration());
    }

    @Test
    @DisplayName("GetSyncState reports generation 0 for a site that has never been wiped")
    void shouldEmitZeroGenerationForNeverWipedSite() {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());

        SyncStateResponse response = blockingStub.getSyncState(SyncStateRequest.newBuilder().build());

        assertEquals(0L, response.getGeneration());
    }

    @Test
    @DisplayName("SessionOpened carries the generation on the PROCEED path")
    void shouldEmitGenerationOnProceed() {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(2)));
        openableBatch(UUID.randomUUID());

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 121L, 2L));

        assertTrue(events.get(0).hasOpened(), "session opened");
        assertEquals(RecoveryAction.PROCEED, events.get(0).getOpened().getAction());
        assertEquals(2L, events.get(0).getOpened().getGeneration());
    }

    @Test
    @DisplayName("SessionOpened carries the generation on the RESUME_FROM path")
    void shouldEmitGenerationOnResume() {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(2)));
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);
        when(batchLifecycle.isBatchInProgress(batchId)).thenReturn(true);

        List<ServerEvent> first = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(collect(first, new CountDownLatch(1)));
        s1.onNext(startAtGeneration(SessionMode.DELTA, 121L, 2L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onError(new RuntimeException("transport drop"));

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, new CountDownLatch(1)));
        s2.onNext(startAtGeneration(SessionMode.DELTA, 121L, 2L));

        assertTrue(second.get(0).hasOpened(), "resume opens the session");
        assertEquals(RecoveryAction.RESUME_FROM, second.get(0).getOpened().getAction());
        assertEquals(2L, second.get(0).getOpened().getGeneration());
    }

    @Test
    @DisplayName("a session carrying a stale generation is refused with GENERATION_MISMATCH")
    void shouldRejectStaleGeneration() {
        // The dangerous case: the client saw epoch 2, crashed before resetting, and reconnects with
        // epoch-1 sequence numbers against a site that has been wiped since.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(2)));

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 121L, 1L));

        assertTrue(events.get(0).hasError(), "stale epoch is refused");
        assertEquals(ErrorCode.GENERATION_MISMATCH, events.get(0).getError().getCode());
        assertEquals(RecoveryAction.NEED_REBASELINE, events.get(0).getError().getAction());
    }

    @Test
    @DisplayName("a FULL_SNAPSHOT carrying a stale generation is refused too")
    void shouldRejectStaleGenerationOnFullSnapshot() {
        // A snapshot is gap-exempt but not epoch-exempt: its seq numbering still comes from the
        // client's pre-wipe counter, which is exactly what the epoch says to throw away.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(2)));

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.FULL_SNAPSHOT, 5000L, 1L));

        assertTrue(events.get(0).hasError(), "stale epoch is refused");
        assertEquals(ErrorCode.GENERATION_MISMATCH, events.get(0).getError().getCode());
    }

    @Test
    @DisplayName("an absent generation (old client) skips the guard")
    void shouldSkipGuardForOldClient() {
        // An old client never sends the field, and absence — not a zero — is what identifies it.
        // It must keep working: it recovers through NEED_REBASELINE, which the wipe raises anyway.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(2)));
        openableBatch(UUID.randomUUID());

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(start(SessionMode.DELTA, 121L));

        assertTrue(events.get(0).hasOpened(), "old client is not refused");
        assertEquals(2L, events.get(0).getOpened().getGeneration());
    }

    @Test
    @DisplayName("a never-wiped site accepts a client that reports generation 0")
    void shouldAcceptMatchingZeroGeneration() {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        openableBatch(UUID.randomUUID());

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 1L, 0L));

        assertTrue(events.get(0).hasOpened(), "session opened");
        assertEquals(0L, events.get(0).getOpened().getGeneration());
    }

    @Test
    @DisplayName("the FIRST wipe of a site is guarded: an explicit generation 0 against server 1 is refused")
    void shouldRejectExplicitZeroAgainstFirstWipe() {
        // The hole presence exists to close (#130 Q2). Every site starts at epoch 0, so the first
        // wipe moves it 0 -> 1 — and a client that correctly echoes the 0 it last saw used to be
        // waved through by a guard that read "0 means old client", precisely when its sequence
        // numbers had just become meaningless.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(1)));

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 121L, 0L));

        assertTrue(events.get(0).hasError(), "an explicit stale zero is refused");
        assertEquals(ErrorCode.GENERATION_MISMATCH, events.get(0).getError().getCode());
        assertEquals(RecoveryAction.NEED_REBASELINE, events.get(0).getError().getAction());
    }

    @Test
    @DisplayName("generation is present on the wire even when it is 0")
    void shouldSetPresenceOnZeroGeneration() {
        // Implicit presence would drop a zero from the encoding entirely, leaving the client unable
        // to tell this server from one too old to know about epochs (#130 Q1).
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        openableBatch(UUID.randomUUID());

        SyncStateResponse response = blockingStub.getSyncState(SyncStateRequest.newBuilder().build());
        assertTrue(response.hasGeneration(), "GetSyncState states the epoch explicitly");

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 1L, 0L));

        assertTrue(events.get(0).getOpened().hasGeneration(), "SessionOpened states it too");
    }

    @Test
    @DisplayName("a client ahead of the server is refused as well")
    void shouldRejectClientAheadOfServer() {
        // Not merely a stale client: a site restored from a backup reads as an older epoch than the
        // client's. Either way the two disagree about which history they are talking about.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(atGeneration(1)));

        List<ServerEvent> events = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> session =
                asyncStub.streamChanges(collect(events, new CountDownLatch(1)));
        session.onNext(startAtGeneration(SessionMode.DELTA, 121L, 9L));

        assertTrue(events.get(0).hasError(), "epoch disagreement is refused either way");
        assertEquals(ErrorCode.GENERATION_MISMATCH, events.get(0).getError().getCode());
    }
}
