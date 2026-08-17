package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The checkpoint fold's heap budget, reserved for the whole process rather than per build
 * (issue #178).
 *
 * <p>{@code delta.checkpoint.max-fold-bytes} (#152) bounds <em>one</em> fold, and one JVM can hold
 * two: {@code CheckpointScheduler}'s {@code ReentrantLock} serializes the cron thread only, while
 * {@code DeltaCheckpointRebuildService} runs {@code rebuildFromFrame} on the separate
 * single-thread {@code deltaRebuildExecutor} (and {@code resumePendingRebuilds()} fires one at
 * startup). Two folds at 45% of the budget each therefore crossed nothing, were refused nothing,
 * and still {@code OOMKilled} the pod with in-flight ingest — the failure #152 exists to replace.
 * It is the same {@code 2 x} the scratch budget reserves for on disk (#131, #138).</p>
 *
 * <p><b>Exclusion rather than a shared running total</b>, which is the ticket's own second option
 * and the reason this class is a semaphore and not an {@code AtomicLong}. A shared total would have
 * to pick a fold to refuse when the sum crosses, and the record that crosses it belongs to whichever
 * build happens to be applying one — so the nightly sweep of a small site could be refused because
 * an operator clicked "rebuild" on a large one, which is a regression against the path #152
 * actually protected. It would also have to say so on {@code delta.checkpoint.builds.aborted},
 * whose documented contract (#153) is aborts that never repair themselves, while a refusal caused
 * by what else is folding repairs itself the moment the neighbour finishes. One fold at a time
 * gives the exact guarantee instead: the configured ceiling is the whole of what the checkpoint
 * path may hold, the victim of a collision is deterministically the build that arrived second, and
 * its outcome is a <b>deferral</b> — nothing about the site's data is concluded, nothing durable is
 * written, and {@code delta.checkpoint.builds.deferred} is a meter of its own.</p>
 *
 * <p>The reservation covers the whole build, not just the fold loop: the folded state is what
 * {@code writeSnapshots} iterates, so it is retained until the last table has been uploaded.</p>
 *
 * <p>The semaphore is <b>fair</b>, which is what bounds the wait. The nightly tick takes and
 * releases the budget once per site, so a forced rebuild queues behind one site's build rather than
 * behind the whole sweep, and vice versa.</p>
 *
 * <p><b>The wait is shutdown-aware</b>, and it has to be: {@code deltaRebuildExecutor} is a
 * {@code ThreadPoolTaskExecutor} with {@code waitForTasksToCompleteOnShutdown(true)} and
 * non-daemon threads, so Spring never interrupts a task parked here — an unaware wait would hold
 * context close for the executor's whole {@code awaitTerminationSeconds} and then time out. It is
 * therefore taken in slices, checking {@link ApplicationShutdownSignal} between them, and a
 * shutdown ends the wait as a deferral that each caller settles against the same signal. Slicing
 * re-queues on a fair semaphore, which would matter under a stream of arrivals; here there are two
 * possible waiters in a JVM, so it costs nothing.</p>
 *
 * <p>Per JVM, deliberately: heap is per pod. A deployment running the sweep on several replicas has
 * that many independent budgets, exactly as it has that many heaps.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointFoldBudget {

    private static final Logger log = LoggerFactory.getLogger(CheckpointFoldBudget.class);

    /** A wait worth mentioning: below this, contention is invisible and uninteresting. */
    private static final long LOG_WAIT_ABOVE_MILLIS = 1_000L;

    /** How long one {@code tryAcquire} may park before the shutdown flag is re-read. */
    private static final long WAIT_SLICE_MILLIS = 500L;

    /** Ceiling on the configured wait, so the nanosecond deadline below cannot overflow. */
    private static final long MAX_WAIT_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final Semaphore budget = new Semaphore(1, true);
    private final ApplicationShutdownSignal shutdownSignal;
    private final DeltaMetrics metrics;
    private final long waitMillis;

    public CheckpointFoldBudget(ApplicationShutdownSignal shutdownSignal,
                                DeltaMetrics metrics,
                                @Value("${delta.checkpoint.fold-wait-seconds:600}") long waitSeconds) {
        this.shutdownSignal = shutdownSignal;
        this.metrics = metrics;
        // Clamped, not merely saturated. toMillis saturates rather than wrapping, but the deadline
        // below is nanoseconds — System.nanoTime() + toNanos(Long.MAX_VALUE) overflows to a large
        // negative, the loop guard is false at once, and an operator who typed a very large number
        // to mean "wait indefinitely" would get one 500 ms slice: exactly the "absurd value becomes
        // no wait at all" this guard exists to prevent, moved one line down (raised in review).
        // A day is past every sensible wait and leaves the nanosecond arithmetic far from its edge.
        this.waitMillis = Math.min(TimeUnit.SECONDS.toMillis(Math.max(0L, waitSeconds)),
                MAX_WAIT_MILLIS);
    }

    /**
     * Run one checkpoint build with the process's fold budget held, waiting for it if another build
     * has it.
     *
     * @param siteId the site whose build wants the budget, for the deferral's message
     * @param build  the build to run while the budget is held
     * @param <T>    what the build returns
     * @return the build's own value
     * @throws BuildDeferredException when the wait was spent without the budget coming free
     */
    public <T> T runExclusively(UUID siteId, Supplier<T> build) {
        return runExclusively(siteId, true, build);
    }

    /**
     * As above, with the wait itself optional.
     *
     * <p>Bounded rather than indefinite: an indefinite wait behind a build that has stalled would
     * freeze the nightly sweep for every site, silently and until the pod is replaced. Spending the
     * wait defers this one site, says so, and lets the tick carry on to the next. It is also what
     * keeps the permit from being able to deadlock anything: a future nested call would spend its
     * wait and be deferred rather than hang, since nothing here is reentrant.</p>
     *
     * <p>{@code mayWait} is how a caller with <em>many</em> builds to run keeps the wait a property
     * of the tick rather than of each site (issue #178, review). {@code CheckpointScheduler} pays
     * one full wait per tick and asks for the budget without waiting afterwards: at 200 sites the
     * alternative is a tick of {@code 200 x fold-wait-seconds}, during which its own
     * {@code tryLock} skips the following nights and retention freezes for every site rather than
     * for the contended one. A zero wait still <em>takes</em> the budget the moment it is free, so
     * the rest of the tick proceeds normally as soon as the neighbour finishes.</p>
     *
     * @param siteId   the site whose build wants the budget, for the deferral's message
     * @param mayWait  {@code false} to take the budget only if it is free right now
     * @param build    the build to run while the budget is held
     * @param <T>      what the build returns
     * @return the build's own value
     * @throws BuildDeferredException when the budget did not come free within the allowed wait
     */
    public <T> T runExclusively(UUID siteId, boolean mayWait, Supplier<T> build) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(mayWait ? waitMillis : 0L);
        long startedAt = System.nanoTime();
        boolean acquired = false;
        boolean endedEarly = false;
        try {
            do {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                acquired = budget.tryAcquire(Math.min(remainingMillis, WAIT_SLICE_MILLIS),
                        TimeUnit.MILLISECONDS);
                // Checked between slices, and only when the budget was not free. A shutdown must
                // not refuse an acquire that would have succeeded at once — the caller has its own
                // check for that, which ends the build as a shutdown rather than as a deferral —
                // but it must end a *wait*: deltaRebuildExecutor waits for its tasks on context
                // close and never interrupts them, so an unaware wait would hold the shutdown for
                // the whole awaitTerminationSeconds and then time out.
                if (!acquired && shutdownSignal.isShuttingDown()) {
                    endedEarly = true;
                    break;
                }
            } while (!acquired && System.nanoTime() < deadlineNanos);
        } catch (InterruptedException e) {
            // Interrupted waiting for the budget: this build has not started, so it is deferred
            // rather than failed. The flag is restored — swallowing it would leave the thread's
            // interrupt state lying to whatever runs next on it.
            Thread.currentThread().interrupt();
            endedEarly = true;
        }
        // The permit is released from a finally that starts here and not after the reporting below:
        // anything thrown between the acquire and the try — the meter, the log — would otherwise
        // leak the process's only permit for the life of the pod, after which every checkpoint
        // build is deferred, no pointer advances and retention freezes for every site. That is
        // wildly out of proportion to its trigger (raised in review).
        try {
            long waitedNanos = System.nanoTime() - startedAt;
            // Recorded whichever way it ended: the near miss — a build that waited nine minutes and
            // then ran — is invisible on delta.checkpoint.duration, because the budget is taken
            // outside phase=total, and it is the signal that precedes the first deferral.
            metrics.recordCheckpointFoldWait(waitedNanos);
            if (!acquired) {
                throw new BuildDeferredException(siteId, waitMillis, endedEarly, mayWait);
            }
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(waitedNanos);
            if (waitedMillis >= LOG_WAIT_ABOVE_MILLIS) {
                log.info("The checkpoint build for site {} waited {} ms for the process's fold "
                        + "budget (delta.checkpoint.max-fold-bytes is reserved for one build at a "
                        + "time)", siteId, waitedMillis);
            }
            return build.get();
        } finally {
            if (acquired) {
                budget.release();
            }
        }
    }

    /**
     * This build did not run because another one held the process's fold budget (issue #178).
     *
     * <p>Public and thrown, like its {@code FramePresenceUnknownException} sibling and for the same
     * reason: an empty fold is indistinguishable from a finished build, so
     * {@code DeltaCheckpointRebuildService} would report a rebuild that never ran and spend the
     * operator's durable {@code rebuild_requested} flag on it.</p>
     *
     * <p><b>Not an abort.</b> Nothing was folded, uploaded, saved or counted against the site, no
     * materialize attempt was spent, and the cause is entirely outside the site — so this is
     * deliberately absent from {@code delta.checkpoint.builds.aborted}, whose tag values are the
     * refusals that never repair themselves. {@code delta.checkpoint.builds.deferred} is the meter
     * for the ones that do.</p>
     *
     * <p>A shutdown and an interrupt arrive here too, which is why every caller settles this against
     * {@link ApplicationShutdownSignal} rather than treating it as a plain deferral: the forced
     * rebuild must keep its durable flag when the process is going away (issue #162), and clearing
     * it would lose the request {@code resumePendingRebuilds()} exists to re-drive.</p>
     */
    public static final class BuildDeferredException extends RuntimeException {

        private final boolean endedEarly;
        private final boolean mayWait;

        BuildDeferredException(UUID siteId, long waitMillis, boolean endedEarly, boolean mayWait) {
            super("The checkpoint build for site " + siteId + " was deferred: another build held the "
                    + "process's fold budget" + why(waitMillis, endedEarly, mayWait)
                    + ". Nothing was folded and nothing was recorded — the pointer, the per-table "
                    + "keys and the frame stay where they were, and the next tick tries again");
            this.endedEarly = endedEarly;
            this.mayWait = mayWait;
        }

        /**
         * Did this build actually spend a wait, or was it only probing?
         *
         * <p>The distinction is what keeps {@code delta.checkpoint.builds.deferred} meaning what its
         * documentation says (raised in review). Once the nightly sweep has spent its wait it visits
         * every remaining site with a single non-blocking probe, so one collision would otherwise
         * add hundreds of increments — and the remedy the meter's own text prescribes, raising
         * {@code delta.checkpoint.fold-wait-seconds}, is the wrong answer for every one of them.
         * Only a spent wait says "the budget was busy for as long as a build is allowed to wait".</p>
         *
         * @return {@code true} when the full configured wait was available and was used up
         */
        public boolean waitWasSpent() {
            return mayWait && !endedEarly;
        }

        /**
         * Was the wait cut short rather than spent?
         *
         * <p>{@code true} when the application began shutting down while this build was waiting, or
         * the thread was interrupted. Neither is contention, so neither belongs on
         * {@code delta.checkpoint.builds.deferred}: a rollout that catches a build waiting would
         * otherwise move an alerting series for a reason that has nothing to do with the fold
         * budget being busy — the same rule that keeps {@code BuildEndedByShutdownException} off
         * every meter (issue #162).</p>
         *
         * @return {@code true} when the wait ended on a shutdown or an interrupt
         */
        public boolean endedEarly() {
            return endedEarly;
        }

        private static String why(long waitMillis, boolean endedEarly, boolean mayWait) {
            if (endedEarly) {
                return " and this thread was interrupted or the application began shutting down "
                        + "while waiting for it";
            }
            if (!mayWait) {
                return " and this build was not allowed to wait (the tick had already spent its "
                        + waitMillis + " ms of delta.checkpoint.fold-wait-seconds on an earlier site)";
            }
            return " for the whole " + waitMillis + " ms of delta.checkpoint.fold-wait-seconds";
        }
    }
}
