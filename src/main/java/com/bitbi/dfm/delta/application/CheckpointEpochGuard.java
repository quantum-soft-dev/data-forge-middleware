package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Serializes a checkpoint build's database writes against a site history wipe (issue #136).
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
 * <p>The guard is the narrow serialization that closes it. Each write runs in its own short
 * transaction that first takes the {@code site_sync_state} row lock the wipe already holds for its
 * whole transaction ({@code DeltaSiteWipeService}), then compares the site's {@code generation} with
 * the one the build read when it started. Only two orderings survive: the write commits before the
 * wipe takes the lock (so the wipe's own deletes remove it), or it waits for the wipe and then sees
 * the bumped epoch and is refused. No S3 traffic happens inside the lock, so the build still holds
 * a connection only for the length of a single statement.</p>
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
     * Run one checkpoint-build write, but only while the site is still on {@code generation}.
     *
     * <p>A site with no {@code site_sync_state} row counts as generation 0: it has never synced, so
     * a wipe of it would destroy nothing, and the row is created by the pointer write itself.</p>
     *
     * @param siteId     site whose epoch the build started from
     * @param generation the {@code generation} that build read
     * @param write      the write to perform under the row lock
     * @throws EpochChangedException when the site was wiped since the build started; the caller must
     *                               discard the whole build rather than retry the write
     */
    @Transactional
    public void inEpoch(UUID siteId, long generation, Runnable write) {
        long current = syncStateRepository.findBySiteIdForUpdate(siteId)
                .map(SiteSyncState::getGeneration)
                .orElse(0L);
        if (current != generation) {
            throw new EpochChangedException(siteId, generation, current);
        }
        write.run();
    }

    /**
     * The site was wiped while the checkpoint being written was still being built, so nothing that
     * build produced may be published.
     */
    public static class EpochChangedException extends RuntimeException {

        private final long expectedGeneration;
        private final long actualGeneration;

        public EpochChangedException(UUID siteId, long expectedGeneration, long actualGeneration) {
            super("Site " + siteId + " moved from generation " + expectedGeneration + " to "
                    + actualGeneration + " while its checkpoint was being built");
            this.expectedGeneration = expectedGeneration;
            this.actualGeneration = actualGeneration;
        }

        /**
         * @return the generation the discarded build started from
         */
        public long getExpectedGeneration() {
            return expectedGeneration;
        }

        /**
         * @return the generation the site is on now
         */
        public long getActualGeneration() {
            return actualGeneration;
        }
    }
}
