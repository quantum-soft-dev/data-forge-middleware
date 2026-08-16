package com.bitbi.dfm.batch.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rescheduling the retention cleanup must not throw at its caller.
 *
 * <p>{@link BatchRetentionScheduler#onScheduleChanged} runs as a synchronous {@code @EventListener}
 * inside the admin transaction that saved the new cron, and since issue #146 the shared scheduler is
 * shut down when the context closes rather than when its bean is destroyed — so an admin request
 * landing in that window gets its {@code schedule} call rejected. Before, only the first call was
 * guarded and the fallback's rejection escaped, rolling the saved schedule back and answering 500.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchRetentionScheduler rescheduling")
class BatchRetentionSchedulerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private BatchRetentionService batchRetentionService;

    @Mock
    private BatchRetentionScheduleService scheduleService;

    private BatchRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchRetentionScheduler(taskScheduler, batchRetentionService, scheduleService, 1000);
    }

    @Test
    @DisplayName("an unusable cron falls back to the default one")
    void shouldFallBackToDefaultCronWhenTheRequestedOneIsUnusable() {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn(mock(ScheduledFuture.class));

        assertDoesNotThrow(() -> scheduler.onScheduleChanged(new BatchRetentionScheduleChangedEvent("nonsense")));

        // The expression is rejected while the trigger is built, so only the fallback reaches the
        // scheduler — the existing behaviour this change must not disturb.
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("a scheduler shut down under the caller does not fail the admin's transaction")
    void shouldNotThrowWhenTheSchedulerItselfIsGone() {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenThrow(new TaskRejectedException("executor did not accept the task",
                        new RejectedExecutionException()));

        assertDoesNotThrow(() -> scheduler.onScheduleChanged(new BatchRetentionScheduleChangedEvent("0 0 3 * * *")),
                "the event listener runs inside the admin's transaction: a rejection while the context "
                        + "is closing must not roll the saved schedule back and answer 500");

        // No retry with the fallback: it would be rejected for the same reason and would blame a
        // second, perfectly good cron expression in the log.
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    }
}
