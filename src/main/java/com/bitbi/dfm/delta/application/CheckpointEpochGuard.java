package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Serializes a checkpoint build's database writes against anything that replaces the site's baseline
 * — a history wipe (issue #136) or a FULL_SNAPSHOT re-baseline (issue #142).
 *
 * <p>{@link CheckpointService} is deliberately not transactional: a build spans the frame download,
 * one download per segment and one upload per table, and a transaction held across those network
 * calls would pin a HikariCP connection for the whole build. The price of that is a build whose
 * writes can land <em>after</em> a wipe committed — re-inserting the {@code checkpoints} rows the
 * wipe deleted and, worse, restoring a pre-wipe {@code last_checkpoint_seq} on a site whose epoch
 * has just restarted at zero. {@code ChangelogRetentionService.prune} keys off that pointer, so the
 * new epoch's segments read as "below checkpoint" and are pruned to the audit window, after which
 * the next build refuses the now-lossy refold and the site's checkpoint pipeline is stuck.</p>
 *
 * <p>An ordinary re-baseline does the same damage by a quieter route. {@code DeltaRebaselineService}
 * deletes every checkpoint row and zeroes the pointer too, and yet must leave {@code generation}
 * alone — that field is the wire epoch and moving it would tell the client to drop its journal and
 * reset its seq counter, which a re-baseline never means (035). Keyed on {@code generation} the
 * guard saw nothing, the build restored the pre-re-baseline pointer, and the <em>next</em> build
 * then found {@code frameExists} true at that seq and seeded the fold from the discarded baseline's
 * frame: rows deleted at the source reappeared in every checkpoint Parquet, with no pruning alarm
 * and no refused refold. Hence the guard keys on {@code baseline_epoch} (issue #142), the counter
 * both operations move, while {@code generation} keeps its wire meaning.</p>
 *
 * <p>The guard is the narrow serialization that closes it. Each write runs in its own short
 * transaction that first takes the {@code site_sync_state} row lock a wipe ({@code
 * DeltaSiteWipeService}) and a re-baseline ({@code DeltaRebaselineService}) both hold for their
 * whole transaction, then compares the site's {@code baseline_epoch} with the one the build read
 * when it started. Only two orderings survive: the write commits before the lock is taken (so the
 * wipe's or re-baseline's own deletes remove it), or it waits and then sees the bumped epoch and is
 * refused. No S3 traffic happens inside the lock, so the build still holds a connection only for the
 * length of a single statement.</p>
 *
 * <p>Uploads already made by a discarded build are left as orphaned objects: the wipe's prefix walk
 * skips anything newer than its own start instant (issue #122), and re-running the wipe is what
 * sweeps them. Orphaned bytes are the accepted cost throughout this path; a resurrected row is not.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class CheckpointEpochGuard {

    private final SiteSyncStateRepository syncStateRepository;

    public CheckpointEpochGuard(SiteSyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    /**
     * Run one checkpoint-build write, but only while the site is still on {@code baselineEpoch}.
     *
     * <p>A site with no {@code site_sync_state} row counts as epoch 0: it has never synced, so a
     * wipe of it would destroy nothing, and the row is created by the pointer write itself.</p>
     *
     * <p>{@link Propagation#REQUIRES_NEW} makes "short transaction" structural rather than a
     * property of today's callers. Joining an ambient transaction would hold the row lock for
     * everything that transaction does next — every S3 download and upload of the build, the exact
     * connection-pinning this design exists to avoid — and a refusal caught by
     * {@code CheckpointService} would leave that transaction marked rollback-only, turning a
     * discarded build into an {@code UnexpectedRollbackException} for its caller. The other side of
     * that choice: a caller that already holds this row's lock would block on itself, which is why
     * the build stays non-transactional (pinned by a test on {@code buildCheckpoint}).</p>
     *
     * @param siteId        site whose epoch the build started from
     * @param baselineEpoch the {@code baseline_epoch} that build read
     * @param write         the write to perform under the row lock
     * @throws EpochChangedException when the site was wiped or re-baselined since the build started;
     *                               the caller must discard the whole build rather than retry the write
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inEpoch(UUID siteId, long baselineEpoch, Runnable write) {
        long current = syncStateRepository.findBySiteIdForUpdate(siteId)
                .map(SiteSyncState::getBaselineEpoch)
                .orElse(0L);
        if (current != baselineEpoch) {
            throw new EpochChangedException(siteId, baselineEpoch, current);
        }
        write.run();
    }

    /**
     * The site's baseline was replaced — wiped or re-baselined — while the checkpoint being written
     * was still being built, so nothing that build produced may be published.
     */
    public static class EpochChangedException extends RuntimeException {

        private final long expectedEpoch;
        private final long actualEpoch;

        public EpochChangedException(UUID siteId, long expectedEpoch, long actualEpoch) {
            super("Site " + siteId + " moved from baseline epoch " + expectedEpoch + " to "
                    + actualEpoch + " while its checkpoint was being built");
            this.expectedEpoch = expectedEpoch;
            this.actualEpoch = actualEpoch;
        }

        /**
         * @return the baseline epoch the discarded build started from
         */
        public long getExpectedEpoch() {
            return expectedEpoch;
        }

        /**
         * @return the baseline epoch the site is on now
         */
        public long getActualEpoch() {
            return actualEpoch;
        }
    }
}
