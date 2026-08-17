package com.bitbi.dfm.delta.application;

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
 * behind the whole sweep, and vice versa. A wait is not cut short by a shutdown, and does not need
 * to be: the holder checks {@code ApplicationShutdownSignal} between tables and before the frame
 * upload, so during a rollout it ends promptly and the waiter inherits the budget only to hit the
 * same checks — on a daemon thread, having written nothing.</p>
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

    private final Semaphore budget = new Semaphore(1, true);
    private final long waitMillis;

    public CheckpointFoldBudget(@Value("${delta.checkpoint.fold-wait-seconds:600}") long waitSeconds) {
        // toMillis saturates rather than wrapping, so an absurd value stays an absurd wait instead
        // of overflowing into a negative one — which tryAcquire would read as "do not wait at all",
        // the opposite of what was asked for.
        this.waitMillis = TimeUnit.SECONDS.toMillis(Math.max(0L, waitSeconds));
    }

    /**
     * Run one checkpoint build with the process's fold budget held, waiting for it if another build
     * has it.
     *
     * <p>Bounded rather than indefinite: an indefinite wait behind a build that has stalled would
     * freeze the nightly sweep for every site, silently and until the pod is replaced. Spending the
     * wait defers this one site, says so, and lets the tick carry on to the next. It is also what
     * keeps the permit from being able to deadlock anything: a future nested call would spend its
     * wait and be deferred rather than hang, since nothing here is reentrant.</p>
     *
     * @param siteId the site whose build wants the budget, for the deferral's message
     * @param build  the build to run while the budget is held
     * @param <T>    what the build returns
     * @return the build's own value
     * @throws BuildDeferredException when the wait was spent without the budget coming free
     */
    public <T> T runExclusively(UUID siteId, Supplier<T> build) {
        long startedAt = System.nanoTime();
        boolean acquired;
        try {
            acquired = budget.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Interrupted waiting for the budget: this build has not started, so it is deferred
            // rather than failed. The flag is restored — swallowing it would leave the thread's
            // interrupt state lying to whatever runs next on it.
            Thread.currentThread().interrupt();
            throw new BuildDeferredException(siteId, waitMillis, true);
        }
        if (!acquired) {
            throw new BuildDeferredException(siteId, waitMillis, false);
        }
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (waitedMillis >= LOG_WAIT_ABOVE_MILLIS) {
            log.info("The checkpoint build for site {} waited {} ms for the process's fold budget "
                    + "(delta.checkpoint.max-fold-bytes is reserved for one build at a time)",
                    siteId, waitedMillis);
        }
        try {
            return build.get();
        } finally {
            budget.release();
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
     */
    public static final class BuildDeferredException extends RuntimeException {

        BuildDeferredException(UUID siteId, long waitMillis, boolean interrupted) {
            super("The checkpoint build for site " + siteId + " was deferred: another build held the "
                    + "process's fold budget"
                    + (interrupted
                            ? " and this thread was interrupted while waiting for it"
                            : " for the whole " + waitMillis + " ms of delta.checkpoint.fold-wait-seconds")
                    + ". Nothing was folded and nothing was recorded — the pointer, the per-table "
                    + "keys and the frame stay where they were, and the next tick tries again");
        }
    }
}
