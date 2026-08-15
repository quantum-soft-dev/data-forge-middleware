package com.bitbi.dfm.delta.application;

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
 * The row-side half of the wipe race (issue #136): a database write belonging to a checkpoint build
 * may only land while the site is still on the epoch that build started from.
 */
class CheckpointEpochGuardTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final CheckpointEpochGuard guard = new CheckpointEpochGuard(syncStateRepository);

    @Test
    void runsTheWriteWhileTheSiteIsStillOnTheSameEpoch() {
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(state(3L)));
        AtomicBoolean written = new AtomicBoolean();

        guard.inEpoch(SITE, 3L, () -> written.set(true));

        assertTrue(written.get());
    }

    @Test
    void refusesTheWriteAfterAWipeBumpedTheEpoch() {
        // The damaging interleaving: the build read generation 0, the wipe committed generation 1,
        // and this write would otherwise re-insert pre-wipe rows and a pre-wipe pointer.
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(state(1L)));
        AtomicBoolean written = new AtomicBoolean();

        CheckpointEpochGuard.EpochChangedException failure =
                assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                        () -> guard.inEpoch(SITE, 0L, () -> written.set(true)));

        assertFalse(written.get(), "a stale-epoch write must not reach the database");
        assertTrue(failure.getMessage().contains(SITE.toString()));
    }

    @Test
    void takesTheRowLockTheWipeAlreadyHolds() {
        // A plain read would let the check pass while the wipe's transaction is still open, and the
        // write would then land right after its commit. The lock is what serializes the two.
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(state(0L)));

        guard.inEpoch(SITE, 0L, () -> {
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

        guard.inEpoch(SITE, 0L, () -> written.set(true));

        assertTrue(written.get());
        assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                () -> guard.inEpoch(SITE, 2L, () -> {
                }));
    }

    @Test
    void guardsTheWriteInsideItsOwnShortTransaction() throws NoSuchMethodException {
        // Without a transaction the pessimistic lock is released the moment it is taken, so the
        // check and the write it protects would no longer be serialized against the wipe. And
        // joining an ambient one would hold that lock across the build's S3 round-trips and make a
        // refusal poison the caller's transaction instead of discarding the build.
        Method inEpoch = CheckpointEpochGuard.class.getMethod("inEpoch", UUID.class, long.class, Runnable.class);
        Transactional transactional = inEpoch.getAnnotation(Transactional.class);

        assertNotNull(transactional, "the epoch check and the write it guards must share a transaction");
        assertFalse(transactional.readOnly(), "the guarded write mutates rows");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                "the row lock must never outlive the single write it protects");
    }

    @Test
    void reportsBothEpochsSoTheDiscardIsTraceable() {
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(state(4L)));

        CheckpointEpochGuard.EpochChangedException failure =
                assertThrows(CheckpointEpochGuard.EpochChangedException.class,
                        () -> guard.inEpoch(SITE, 2L, () -> {
                        }));

        assertEquals(2L, failure.getExpectedGeneration());
        assertEquals(4L, failure.getActualGeneration());
    }

    private static SiteSyncState state(long generation) {
        SiteSyncState state = SiteSyncState.initial(SITE);
        for (long i = 0; i < generation; i++) {
            state.resetForWipe();
        }
        return state;
    }
}
