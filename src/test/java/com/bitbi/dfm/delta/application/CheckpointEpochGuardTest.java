package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The row-side half of the wipe race (issue #136), widened to the re-baseline (issue #142): a
 * database write belonging to a checkpoint build may only land while the site is still on the epoch
 * pair that build started from — the wire {@code generation} and the server-internal
 * {@code baseline_epoch}, neither of which subsumes the other across a rolling deployment.
 */
class CheckpointEpochGuardTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final CheckpointEpochGuard guard = new CheckpointEpochGuard(syncStateRepository);

    @Test
    void runsTheWriteWhileTheSiteIsStillOnTheSameEpoch() {
        SiteSyncState unchanged = wiped(3L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(unchanged));
        AtomicBoolean written = new AtomicBoolean();

        guard.inEpoch(SITE, SiteEpoch.of(unchanged), () -> written.set(true));

        assertTrue(written.get());
    }

    @Test
    void refusesTheWriteWhenOnlyTheGenerationMoved() {
        // The rolling-deployment case, and the reason the guard compares a pair rather than the
        // "strongest" counter. A pod that predates baseline_epoch bumps generation alone, so during
        // the mixed-version window a wipe issued from an old pod leaves baseline_epoch untouched —
        // and a new pod watching only that would let the pre-wipe pointer back in, re-opening #136
        // through the fix for #142.
        // Mocked rather than built through resetForWipe: this state is precisely the one the current
        // entity cannot produce — a wipe that moved the generation and left the baseline epoch at 0.
        SiteSyncState wipedByAnOldPod = mock(SiteSyncState.class);
        when(wipedByAnOldPod.getGeneration()).thenReturn(1L);
        when(wipedByAnOldPod.getBaselineEpoch()).thenReturn(0L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wipedByAnOldPod));
        AtomicBoolean written = new AtomicBoolean();

        assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                () -> guard.inEpoch(SITE, SiteEpoch.INITIAL, () -> written.set(true)));

        assertFalse(written.get(), "a generation-only move is still a destroyed history");
    }

    @Test
    void refusesTheWriteAfterARebaselineThatLeftTheGenerationAlone() {
        // Issue #142. A re-baseline discards every checkpoint and zeroes the pointer just like a
        // wipe, but must not move the generation — that is the wire signal telling the client to
        // reset its counters (035). Keying the guard on the generation therefore let this write
        // through, and it restored the pointer of the baseline that had just been discarded.
        SiteSyncState rebaselined = SiteSyncState.initial(SITE);
        rebaselined.resetForRebaseline(0L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(rebaselined));
        AtomicBoolean written = new AtomicBoolean();

        assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                () -> guard.inEpoch(SITE, baseline(0L), () -> written.set(true)));

        assertEquals(0L, rebaselined.getGeneration(), "the wire epoch must be untouched by a re-baseline");
        assertFalse(written.get(), "a write from the discarded baseline must not reach the database");
    }

    @Test
    void refusesTheWriteAfterAWipeBumpedTheEpoch() {
        // The damaging interleaving: the build read epoch 0, the wipe committed epoch 1, and this
        // write would otherwise re-insert pre-wipe rows and a pre-wipe pointer.
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wiped(1L)));
        AtomicBoolean written = new AtomicBoolean();

        CheckpointEpochGuard.EpochChangedException failure =
                assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                        () -> guard.inEpoch(SITE, SiteEpoch.INITIAL, () -> written.set(true)));

        assertFalse(written.get(), "a stale-epoch write must not reach the database");
        assertTrue(failure.getMessage().contains(SITE.toString()));
    }

    @Test
    void takesTheRowLockTheWipeAlreadyHolds() {
        // A plain read would let the check pass while the wipe's transaction is still open, and the
        // write would then land right after its commit. The lock is what serializes the two.
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wiped(0L)));

        guard.inEpoch(SITE, SiteEpoch.INITIAL, () -> {
        });

        verify(syncStateRepository).findBySiteIdForUpdate(SITE);
        verify(syncStateRepository, never()).findBySiteId(SITE);
    }

    @Test
    void treatsAMissingRowAsTheZeroEpoch() {
        // A site that never synced has no row. There is nothing for a wipe to destroy either, so
        // the build proceeds; the row is created by the pointer write itself.
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.empty());
        AtomicBoolean written = new AtomicBoolean();

        guard.inEpoch(SITE, SiteEpoch.INITIAL, () -> written.set(true));

        assertTrue(written.get());
        assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                () -> guard.inEpoch(SITE, baseline(2L), () -> {
                }));
    }

    @Test
    void guardsTheWriteInsideItsOwnShortTransaction() throws NoSuchMethodException {
        // Without a transaction the pessimistic lock is released the moment it is taken, so the
        // check and the write it protects would no longer be serialized against the wipe. And
        // joining an ambient one would hold that lock across the build's S3 round-trips and make a
        // refusal poison the caller's transaction instead of discarding the build.
        Method inEpoch = CheckpointEpochGuard.class.getMethod("inEpoch", UUID.class, SiteEpoch.class, Runnable.class);
        Transactional transactional = inEpoch.getAnnotation(Transactional.class);

        assertNotNull(transactional, "the epoch check and the write it guards must share a transaction");
        assertFalse(transactional.readOnly(), "the guarded write mutates rows");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                "the row lock must never outlive the single write it protects");
    }

    @Test
    void reportsBothEpochsSoTheDiscardIsTraceable() {
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wiped(4L)));

        CheckpointEpochGuard.EpochChangedException failure =
                assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                        () -> guard.inEpoch(SITE, baseline(2L), () -> {
                        }));

        assertEquals(baseline(2L), failure.getExpectedEpoch());
        assertEquals(4L, failure.getActualEpoch().baselineEpoch());
    }

    /** The epoch pair of a site that has only ever been re-baselined {@code n} times. */
    private static SiteEpoch baseline(long n) {
        return new SiteEpoch(0L, n);
    }

    private static SiteSyncState wiped(long epochs) {
        SiteSyncState state = SiteSyncState.initial(SITE);
        for (long i = 0; i < epochs; i++) {
            state.resetForWipe();
        }
        return state;
    }
}
