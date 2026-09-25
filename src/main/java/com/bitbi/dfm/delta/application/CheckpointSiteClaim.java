package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One replica visits a site's checkpoint at a time (issue #345).
 *
 * <p>{@code CheckpointScheduler} fires on every replica at the same second, and nothing kept the
 * pods apart: its {@code ReentrantLock} and {@link CheckpointFoldBudget} are per JVM. Three pods
 * built one site's first checkpoint on the test cluster — the same 275 MB frame uploaded three
 * times, two of the builds ending on {@code uk_checkpoint_site_table} and logged as a failure — and
 * on the following nights two incremental builds of the same site both ran to completion, each
 * pruning behind the other. {@code CheckpointEpochGuard} compares the baseline epoch, which both
 * builds share, not who owns the site.</p>
 *
 * <p>The claim is a row of {@code site_sync_state} with a token and a lease, the shape
 * {@code batch_parquet_artifacts} already uses (#036/#040). It is taken with one conditional
 * statement in its own short transaction and <b>no connection is held across the visit</b>, which
 * is the rule #164 set for everything around S3: a session-level advisory lock would have held one
 * for the length of a 30-minute build. The lease is renewed every third of its length while the
 * visit is alive, so it measures liveness rather than capping how long a build may take, and a pod
 * that dies mid-build — HPA scale-down and preemption do this at night — loses the site once the
 * lease lapses. Only the token that holds the claim can renew or release it, so a pod that was
 * merely paused cannot free a site somebody else has since taken over.</p>
 *
 * <p><b>What it guarantees and what it does not.</b> It is the <em>visit</em> that is exclusive:
 * the build, the changelog prune that follows it in the same visit, and a forced rebuild. It is a
 * lease, not a fence: a holder stalled for longer than the lease (a stop-the-world pause, a
 * database it cannot reach to renew) can find its site taken over and the two builds overlapping,
 * which is exactly the behaviour before this class — correct, because the pointer is monotonic and
 * the S3 keys are deterministic, and wasteful. The takeover is logged at WARN by the renewal that
 * notices it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointSiteClaim {

    private static final Logger log = LoggerFactory.getLogger(CheckpointSiteClaim.class);

    /** How often a forced rebuild re-asks for a claim held elsewhere. One statement per poll. */
    private static final long DEFAULT_POLL_MILLIS = 2_000L;

    /** Ceiling on the configured wait, so the nanosecond deadline cannot overflow (#178's clamp). */
    private static final long MAX_WAIT_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final SiteSyncStateRepository repository;
    private final ApplicationShutdownSignal shutdownSignal;
    private final long leaseSeconds;
    private final long waitMillis;
    private final long pollMillis;
    private final ScheduledExecutorService renewals;

    @Autowired
    public CheckpointSiteClaim(SiteSyncStateRepository repository,
                               ApplicationShutdownSignal shutdownSignal,
                               @Value("${delta.checkpoint.claim-lease-seconds:600}") long leaseSeconds,
                               @Value("${delta.checkpoint.claim-wait-seconds:600}") long waitSeconds) {
        this(repository, shutdownSignal, leaseSeconds, waitSeconds, DEFAULT_POLL_MILLIS);
    }

    CheckpointSiteClaim(SiteSyncStateRepository repository,
                        ApplicationShutdownSignal shutdownSignal,
                        long leaseSeconds,
                        long waitSeconds,
                        long pollMillis) {
        // Refused rather than clamped (#185/#251): a lease of 0 would be expired the instant it was
        // taken, so every replica would take every site — the double build, configured back in.
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException(
                    "delta.checkpoint.claim-lease-seconds must be at least 1, but was " + leaseSeconds);
        }
        this.repository = repository;
        this.shutdownSignal = shutdownSignal;
        this.leaseSeconds = leaseSeconds;
        this.waitMillis = Math.min(TimeUnit.SECONDS.toMillis(Math.max(0L, waitSeconds)), MAX_WAIT_MILLIS);
        this.pollMillis = Math.max(1L, pollMillis);
        // A thread of its own rather than the scheduler pool: a renewal must not queue behind the
        // very ticks whose sites it keeps claimed. Daemon, like batch-parquet-lease, so it can never
        // hold the JVM open; it holds a connection for one UPDATE every lease/3.
        this.renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "checkpoint-site-claim-lease");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Run {@code work} if this replica can claim the site now, and skip it otherwise.
     *
     * <p>For the nightly sweep: another replica already visiting the site is doing exactly this
     * work, so a skip is the correct outcome — not a failure, not a deferral, nothing to record.</p>
     *
     * @param siteId site identifier
     * @param work   the visit to run while the site is held; must not return {@code null}, which
     *               would be indistinguishable from a skip
     * @param <T>    what the visit returns
     * @return the visit's value, or empty when another replica holds the site
     */
    public <T> Optional<T> runIfFree(UUID siteId, Supplier<T> work) {
        UUID token = UUID.randomUUID();
        if (repository.claimCheckpointSite(siteId, token, leaseSeconds) == 0) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(runClaimed(siteId, token, work),
                "a claimed visit must return a value, or it reads as a site claimed elsewhere"));
    }

    /**
     * Run {@code work} under the site's claim, waiting up to {@code delta.checkpoint.claim-wait-seconds}
     * for another replica's visit to finish.
     *
     * <p>For the forced rebuild, which has no next tick to fall back on: skipping would lose the
     * operator's request. The wait is taken in polls that re-read {@link ApplicationShutdownSignal},
     * because {@code deltaRebuildExecutor} waits for its tasks on context close and never interrupts
     * them (#178's reason for slicing the fold-budget wait).</p>
     *
     * @param siteId site identifier
     * @param work   the visit to run while the site is held
     * @param <T>    what the visit returns
     * @return the visit's own value
     * @throws SiteClaimedElsewhereException when the site did not come free within the wait
     */
    public <T> T runWhenFree(UUID siteId, Supplier<T> work) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        UUID token = UUID.randomUUID();
        while (repository.claimCheckpointSite(siteId, token, leaseSeconds) == 0) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (shutdownSignal.isShuttingDown()) {
                throw new SiteClaimedElsewhereException(siteId, waitMillis, true);
            }
            if (remainingMillis <= 0) {
                throw new SiteClaimedElsewhereException(siteId, waitMillis, false);
            }
            try {
                Thread.sleep(Math.min(pollMillis, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SiteClaimedElsewhereException(siteId, waitMillis, true);
            }
        }
        return runClaimed(siteId, token, work);
    }

    private <T> T runClaimed(UUID siteId, UUID token, Supplier<T> work) {
        ScheduledFuture<?> renewal = null;
        try {
            renewal = renewWhileRunning(siteId, token);
            return work.get();
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
            release(siteId, token);
        }
    }

    private ScheduledFuture<?> renewWhileRunning(UUID siteId, UUID token) {
        long periodMillis = Math.max(1L, TimeUnit.SECONDS.toMillis(leaseSeconds) / 3L);
        return renewals.scheduleAtFixedRate(() -> {
            try {
                if (repository.renewCheckpointSiteClaim(siteId, token, leaseSeconds) == 0) {
                    // The lease lapsed before this renewal landed and another replica took the site.
                    // Nothing stops this visit mid-flight, so the two now overlap — the pre-#345
                    // behaviour, correct and wasteful. Said once per renewal so it is not missed.
                    log.warn("The checkpoint claim on site {} was taken over by another replica while "
                            + "this one was still visiting it: its lease "
                            + "(delta.checkpoint.claim-lease-seconds = {}) lapsed without a renewal. "
                            + "The two visits may overlap until this one ends", siteId, leaseSeconds);
                }
            } catch (RuntimeException e) {
                // A missed renewal is not yet a lost claim: the lease is three periods long.
                log.warn("Could not renew the checkpoint claim on site {}: {}", siteId, e.getMessage());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void release(UUID siteId, UUID token) {
        try {
            repository.releaseCheckpointSiteClaim(siteId, token);
        } catch (RuntimeException e) {
            // Never allowed to replace the visit's own outcome — the finally this runs in may be
            // unwinding the exception that says what actually went wrong. An unreleased claim costs
            // at most one lease before the next replica takes the site.
            log.warn("Could not release the checkpoint claim on site {}; it lapses in at most {} s: {}",
                    siteId, leaseSeconds, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        renewals.shutdownNow();
    }

    /**
     * The forced rebuild did not run because another replica held the site for the whole wait, or
     * the wait was cut short (issue #345).
     *
     * <p>Not a failure of the site: nothing was folded, uploaded or recorded. Thrown rather than
     * returned for the reason every other "did not run" ending of the forced path is (#157, #178):
     * {@code DeltaCheckpointRebuildService} must not report a rebuild that never ran.</p>
     */
    public static final class SiteClaimedElsewhereException extends RuntimeException {

        private final boolean endedEarly;

        SiteClaimedElsewhereException(UUID siteId, long waitMillis, boolean endedEarly) {
            super("The checkpoint of site " + siteId + " is being visited by another replica"
                    + (endedEarly
                    ? ", and this thread was interrupted or the application began shutting down while "
                    + "waiting for it"
                    : " for the whole " + waitMillis + " ms of delta.checkpoint.claim-wait-seconds")
                    + ". Nothing was folded and nothing was recorded here");
            this.endedEarly = endedEarly;
        }

        /**
         * @return {@code true} when the full configured wait was used up; {@code false} when a
         *         shutdown or an interrupt ended it early
         */
        public boolean waitWasSpent() {
            return !endedEarly;
        }
    }
}
