package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #178 — the checkpoint fold's heap budget is a reservation for the whole process, not a
 * ceiling each build gets a fresh copy of.
 *
 * <p>{@code delta.checkpoint.max-fold-bytes} bounds one fold (#152). Two folds at 45% of it each
 * cross nothing and still exhaust the heap between them, which one JVM can produce whenever a
 * forced rebuild runs beside the nightly sweep. This budget is what makes the per-build ceiling a
 * per-process one: one fold is live at a time, so the configured number is the whole of what the
 * checkpoint path may hold.</p>
 */
class CheckpointFoldBudgetTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID OTHER_SITE = UUID.randomUUID();

    private volatile boolean shuttingDown;
    private final ApplicationShutdownSignal shutdownSignal = new ApplicationShutdownSignal() {
        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeltaMetrics metrics = new DeltaMetrics(registry);

    private CheckpointFoldBudget budgetWaiting(long seconds) {
        return new CheckpointFoldBudget(shutdownSignal, metrics, seconds);
    }

    @Test
    void runsOneFoldAtATime() throws Exception {
        CheckpointFoldBudget budget = budgetWaiting(30L);
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        AtomicBoolean secondRanWhileTheFirstHeldIt = new AtomicBoolean();
        AtomicBoolean firstIsRunning = new AtomicBoolean();

        Thread first = new Thread(() -> budget.runExclusively(SITE, () -> {
            firstIsRunning.set(true);
            firstIsInside.countDown();
            await(letTheFirstFinish);
            firstIsRunning.set(false);
            return null;
        }));
        first.start();
        assertTrue(firstIsInside.await(5, TimeUnit.SECONDS), "the first fold never started");

        Thread second = new Thread(() -> budget.runExclusively(OTHER_SITE, () -> {
            secondRanWhileTheFirstHeldIt.set(firstIsRunning.get());
            return null;
        }));
        second.start();
        // Long enough that a budget which let both in would have shown it.
        Thread.sleep(200L);
        assertTrue(second.isAlive(), "the second fold must wait, not run beside the first");

        letTheFirstFinish.countDown();
        first.join(5_000L);
        second.join(5_000L);
        assertFalse(second.isAlive(), "the second fold never got the budget after it was released");
        assertFalse(secondRanWhileTheFirstHeldIt.get(),
                "two folds held the process's heap budget at once");
    }

    @Test
    void defersTheSecondBuildOnceTheWaitIsSpent() throws Exception {
        // The outcome is a deferral, never an abort: what happened is that another build was
        // holding the budget, which says nothing about this site's own data.
        CheckpointFoldBudget budget = budgetWaiting(0L);

        withTheBudgetHeld(budget, () -> {
            AtomicBoolean secondRan = new AtomicBoolean();
            CheckpointFoldBudget.BuildDeferredException deferred = assertThrows(
                    CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, () -> {
                        secondRan.set(true);
                        return null;
                    }));

            assertFalse(secondRan.get(), "a deferred build must not run");
            assertTrue(deferred.getMessage().contains(OTHER_SITE.toString()),
                    "the deferral names the site it was for: " + deferred.getMessage());
        });
    }

    @Test
    void takesTheBudgetWithoutWaitingWhenTheCallerSaysNotTo() throws Exception {
        // The tick's policy (review of #178): after one spent wait the sweep keeps visiting sites
        // but stops paying the wait, so a build that never finishes costs one wait and not one per
        // remaining site. A free budget must still be taken immediately.
        CheckpointFoldBudget budget = budgetWaiting(3_600L);
        AtomicInteger ran = new AtomicInteger();

        budget.runExclusively(SITE, false, ran::incrementAndGet);
        assertEquals(1, ran.get(), "a free budget is taken even when waiting is not allowed");

        withTheBudgetHeld(budget, () -> {
            long startedAt = System.nanoTime();
            assertThrows(CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, false, ran::incrementAndGet));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(1, ran.get(), "a deferred build must not run");
            assertTrue(waitedMillis < 2_000L,
                    "the hour-long wait must not have been paid: " + waitedMillis);
        });
    }

    @Test
    void endsTheWaitWhenTheApplicationStartsShuttingDown() throws Exception {
        // deltaRebuildExecutor waits for its tasks on context close and never interrupts them
        // (AsyncConfiguration), so a wait that ignored the signal would hold the shutdown for the
        // whole awaitTerminationSeconds and then time out — and, once it inherited the budget,
        // would fold a whole site during the termination grace period.
        CheckpointFoldBudget budget = budgetWaiting(3_600L);

        withTheBudgetHeld(budget, () -> {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread waiter = new Thread(() -> budget.runExclusively(OTHER_SITE, () -> null));
            waiter.setUncaughtExceptionHandler((thread, failure) -> thrown.set(failure));
            waiter.start();
            Thread.sleep(200L);
            assertTrue(waiter.isAlive(), "fixture: the waiter must be waiting");

            shuttingDown = true;
            waiter.join(10_000L);

            assertFalse(waiter.isAlive(), "the wait must end when the context starts closing");
            assertNotNull(thrown.get(), "the shutdown must end the wait as a deferral");
            assertEquals(CheckpointFoldBudget.BuildDeferredException.class, thrown.get().getClass());
        });
    }

    @Test
    void doesNotLeakTheBudgetWhenReportingTheWaitFails() {
        // The permit must be released from a finally that starts at the acquire, not after the
        // meter and the log: leaking the process's only permit defers every checkpoint build for
        // the life of the pod, freezing retention for every site — wildly out of proportion to a
        // failing meter (raised in review).
        AtomicBoolean explode = new AtomicBoolean(true);
        DeltaMetrics exploding = new DeltaMetrics(new SimpleMeterRegistry()) {
            @Override
            public void recordCheckpointFoldWait(long nanos) {
                if (explode.getAndSet(false)) {
                    throw new IllegalStateException("the registry is unhappy");
                }
            }
        };
        CheckpointFoldBudget budget = new CheckpointFoldBudget(shutdownSignal, exploding, 0L);

        assertThrows(IllegalStateException.class, () -> budget.runExclusively(SITE, () -> null));

        AtomicInteger ran = new AtomicInteger();
        budget.runExclusively(OTHER_SITE, ran::incrementAndGet);
        assertEquals(1, ran.get(), "the budget was leaked by a failure between acquire and try");
    }

    @Test
    void tellsASpentWaitFromANonBlockingProbe() throws Exception {
        // Only a spent wait is contention worth counting: once the sweep has spent its wait it
        // probes every remaining site without waiting, and counting those would add hundreds of
        // increments to one collision — for which the meter's prescribed remedy, raising the wait,
        // is the wrong answer (raised in review).
        CheckpointFoldBudget budget = budgetWaiting(1L);

        withTheBudgetHeld(budget, () -> {
            CheckpointFoldBudget.BuildDeferredException probe = assertThrows(
                    CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, false, () -> null));
            assertFalse(probe.waitWasSpent(), "a build not allowed to wait did not spend one");
            assertFalse(probe.endedEarly(), "and it was not cut short either");

            CheckpointFoldBudget.BuildDeferredException spent = assertThrows(
                    CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, true, () -> null));
            assertTrue(spent.waitWasSpent(), "a build that waited its full second did spend one");
        });
    }

    @Test
    void releasesTheBudgetWhenTheBuildThrows() {
        // A fold refused for its own size (#152) or a build discarded by the epoch guard must not
        // take the process's budget with it — the next site of the same tick would be deferred for
        // ever after.
        CheckpointFoldBudget budget = budgetWaiting(0L);

        assertThrows(IllegalStateException.class,
                () -> budget.runExclusively(SITE, () -> {
                    throw new IllegalStateException("the fold outgrew its budget");
                }));

        AtomicInteger ran = new AtomicInteger();
        budget.runExclusively(OTHER_SITE, ran::incrementAndGet);
        assertEquals(1, ran.get(), "the budget stayed held after a failed build");
    }

    @Test
    void recordsEveryWaitOnItsOwnMeter() throws Exception {
        // The band below the ceiling, the same reason delta.checkpoint.fold.bytes exists beside
        // max-fold-bytes: the budget is taken outside phase=total, so a build that waited nine
        // minutes and then ran is invisible on delta.checkpoint.duration and does not touch
        // delta.checkpoint.builds.deferred either.
        CheckpointFoldBudget budget = budgetWaiting(0L);

        budget.runExclusively(SITE, () -> null);
        assertEquals(1L, waitTimer().count(), "an uncontended build records a sample too");

        withTheBudgetHeld(budget, () -> assertThrows(
                CheckpointFoldBudget.BuildDeferredException.class,
                () -> budget.runExclusively(OTHER_SITE, () -> null)));

        assertEquals(3L, waitTimer().count(),
                "the deferred attempt and the holder's own acquire are recorded as well");
    }

    @Test
    void doesNotTurnAnAbsurdlyLongWaitIntoNoWaitAtAll() throws Exception {
        // Two arithmetic traps, one after the other. Seconds to milliseconds overflows for a large
        // enough key, and a negative timeout is read by tryAcquire as "do not wait"; saturating
        // that then moved the overflow into the nanosecond deadline, where the loop guard is false
        // at once and the wait collapses to a single slice. Both end the same way: an operator who
        // typed a very large number to mean "wait indefinitely" silently gets no wait.
        CheckpointFoldBudget budget = budgetWaiting(Long.MAX_VALUE);

        withTheBudgetHeld(budget, () -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread second = new Thread(() -> budget.runExclusively(OTHER_SITE, () -> null));
            second.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
            second.start();
            // Comfortably past one WAIT_SLICE_MILLIS: at 300 ms this passed even with the deadline
            // overflow in place, which is what made the first version of it vacuous.
            Thread.sleep(2_000L);

            assertTrue(second.isAlive(), "a saturating wait must still be a wait");
            assertNull(failure.get(), "nothing may be deferred while the wait is unspent");
            shuttingDown = true;
            second.join(10_000L);
        });
    }

    @Test
    void tellsAWaitThatWasCutShortFromOneThatWasSpent() throws Exception {
        // The two must not be counted alike: a shutdown that lands on a waiting build is not
        // contention, and putting it on delta.checkpoint.builds.deferred would move an alerting
        // series on every rollout (issue #162's rule, raised in review).
        CheckpointFoldBudget spent = budgetWaiting(0L);
        withTheBudgetHeld(spent, () -> {
            CheckpointFoldBudget.BuildDeferredException deferred = assertThrows(
                    CheckpointFoldBudget.BuildDeferredException.class,
                    () -> spent.runExclusively(OTHER_SITE, () -> null));
            assertFalse(deferred.endedEarly(), "a spent wait is contention and must be counted");
        });

        CheckpointFoldBudget cutShort = budgetWaiting(3_600L);
        withTheBudgetHeld(cutShort, () -> {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread waiter = new Thread(() -> cutShort.runExclusively(OTHER_SITE, () -> null));
            waiter.setUncaughtExceptionHandler((thread, failure) -> thrown.set(failure));
            waiter.start();
            Thread.sleep(200L);
            shuttingDown = true;
            waiter.join(10_000L);

            assertTrue(((CheckpointFoldBudget.BuildDeferredException) thrown.get()).endedEarly(),
                    "a wait ended by the shutdown is not contention");
        });
    }

    @Test
    void treatsANegativeWaitAsNoWait() throws Exception {
        // Nothing sensible to do with it, and silently waiting for ever would be the worst of the
        // options: the nightly sweep would stall behind one stuck build with no signal.
        CheckpointFoldBudget budget = budgetWaiting(-5L);

        withTheBudgetHeld(budget, () -> {
            long startedAt = System.nanoTime();
            assertThrows(CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, () -> null));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(waitedMillis < 2_000L,
                    "a negative wait must not become a long one: " + waitedMillis);
        });
    }

    @Test
    void namesTheKeyThatGovernsTheWaitInTheDeferral() throws Exception {
        // The operator's first question is whether the wait is too short for this deployment's
        // builds, so the key has to be in the line that reports the deferral.
        CheckpointFoldBudget budget = budgetWaiting(1L);

        withTheBudgetHeld(budget, () -> {
            CheckpointFoldBudget.BuildDeferredException deferred = assertThrows(
                    CheckpointFoldBudget.BuildDeferredException.class,
                    () -> budget.runExclusively(OTHER_SITE, () -> null));

            assertNotNull(deferred.getMessage());
            assertTrue(deferred.getMessage().contains("delta.checkpoint.fold-wait-seconds"),
                    "the deferral names the key that governs the wait: " + deferred.getMessage());
        });
    }

    private Timer waitTimer() {
        return registry.get("delta.checkpoint.fold.wait").timer();
    }

    /** Hold the budget on another thread for the length of the body. */
    private void withTheBudgetHeld(CheckpointFoldBudget budget, ThrowingRunnable body) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        Thread holder = new Thread(() -> budget.runExclusively(SITE, () -> {
            held.countDown();
            await(letGo);
            return null;
        }));
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "fixture: the budget was never taken");
        try {
            body.run();
        } finally {
            letGo.countDown();
            holder.join(5_000L);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
