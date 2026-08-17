package com.bitbi.dfm.delta.application;

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

    @Test
    void runsOneFoldAtATime() throws Exception {
        CheckpointFoldBudget budget = new CheckpointFoldBudget(30L);
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
        CheckpointFoldBudget budget = new CheckpointFoldBudget(0L);
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);

        Thread first = new Thread(() -> budget.runExclusively(SITE, () -> {
            firstIsInside.countDown();
            await(letTheFirstFinish);
            return null;
        }));
        first.start();
        assertTrue(firstIsInside.await(5, TimeUnit.SECONDS), "the first fold never started");

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
        letTheFirstFinish.countDown();
        first.join(5_000L);
    }

    @Test
    void releasesTheBudgetWhenTheBuildThrows() {
        // A fold refused for its own size (#152) or a build discarded by the epoch guard must not
        // take the process's budget with it — the next site of the same tick would be deferred for
        // ever after.
        CheckpointFoldBudget budget = new CheckpointFoldBudget(0L);

        assertThrows(IllegalStateException.class,
                () -> budget.runExclusively(SITE, () -> {
                    throw new IllegalStateException("the fold outgrew its budget");
                }));

        AtomicInteger ran = new AtomicInteger();
        budget.runExclusively(OTHER_SITE, ran::incrementAndGet);
        assertEquals(1, ran.get(), "the budget stayed held after a failed build");
    }

    @Test
    void takesNoBudgetFromASecondCallOnceTheFirstReturned() {
        CheckpointFoldBudget budget = new CheckpointFoldBudget(0L);
        AtomicReference<String> answered = new AtomicReference<>();

        assertNull(budget.runExclusively(SITE, () -> null), "the build's own value is returned");
        answered.set(budget.runExclusively(SITE, () -> "second"));

        assertEquals("second", answered.get());
    }

    @Test
    void treatsANegativeWaitAsNoWait() throws Exception {
        // Nothing sensible to do with it, and silently waiting for ever would be the worst of the
        // options: the nightly sweep would stall behind one stuck build with no signal.
        CheckpointFoldBudget budget = new CheckpointFoldBudget(-5L);
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);

        Thread first = new Thread(() -> budget.runExclusively(SITE, () -> {
            firstIsInside.countDown();
            await(letTheFirstFinish);
            return null;
        }));
        first.start();
        assertTrue(firstIsInside.await(5, TimeUnit.SECONDS), "the first fold never started");

        long startedAt = System.nanoTime();
        assertThrows(CheckpointFoldBudget.BuildDeferredException.class,
                () -> budget.runExclusively(OTHER_SITE, () -> null));
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(waitedMillis < 2_000L, "a negative wait must not become a long one: " + waitedMillis);
        letTheFirstFinish.countDown();
        first.join(5_000L);
    }

    @Test
    void namesTheWaitItSpentInTheDeferral() throws Exception {
        // The operator's first question is whether the wait is too short for this deployment's
        // builds, so the number has to be in the line that reports the deferral.
        CheckpointFoldBudget budget = new CheckpointFoldBudget(1L);
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);

        Thread first = new Thread(() -> budget.runExclusively(SITE, () -> {
            firstIsInside.countDown();
            await(letTheFirstFinish);
            return null;
        }));
        first.start();
        assertTrue(firstIsInside.await(5, TimeUnit.SECONDS), "the first fold never started");

        CheckpointFoldBudget.BuildDeferredException deferred = assertThrows(
                CheckpointFoldBudget.BuildDeferredException.class,
                () -> budget.runExclusively(OTHER_SITE, () -> null));

        assertNotNull(deferred.getMessage());
        assertTrue(deferred.getMessage().contains("delta.checkpoint.fold-wait-seconds"),
                "the deferral names the key that governs the wait: " + deferred.getMessage());
        letTheFirstFinish.countDown();
        first.join(5_000L);
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
