package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.infrastructure.events.BatchEventListener;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 031 (T09) — batch terminal events must only reach plugins after their transaction commits.
 *
 * <p>This is load-bearing, not stylistic. {@code BatchLifecycleService.completeBatch} publishes its
 * event <em>before</em> the flush that can fail on optimistic locking, so when a session loses the
 * race to the timeout sweeper the completion event has already been published — it is the
 * {@code AFTER_COMMIT} phase, plus the rollback, that stops it from being delivered. Downgrade these
 * to a plain {@code @EventListener} and a batch that was reclaimed by the sweeper would also dispatch
 * BATCH_COMPLETED to plugins: two terminal events for one batch, which is the bug 031/T09 fixed.</p>
 */
@DisplayName("BatchEventListener transaction phase")
class BatchEventListenerPhaseTest {

    @Test
    @DisplayName("should consume BatchCompletedEvent only after commit")
    void completedIsAfterCommit() {
        assertAfterCommit("onBatchCompleted", BatchCompletedEvent.class);
    }

    @Test
    @DisplayName("should consume BatchExpiredEvent only after commit")
    void expiredIsAfterCommit() {
        assertAfterCommit("onBatchExpired", BatchExpiredEvent.class);
    }

    private static void assertAfterCommit(String methodName, Class<?> eventType) {
        Method handler;
        try {
            handler = BatchEventListener.class.getDeclaredMethod(methodName, eventType);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("BatchEventListener." + methodName + " is gone — if the handler was "
                    + "renamed, update this test rather than deleting it: the AFTER_COMMIT phase is what "
                    + "stops a rolled-back completion from reaching plugins", e);
        }

        TransactionalEventListener annotation = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation)
                .as("%s must be a @TransactionalEventListener; a plain @EventListener would dispatch "
                        + "events published inside transactions that later roll back", methodName)
                .isNotNull();
        assertThat(annotation.phase())
                .as("%s must consume events only AFTER_COMMIT", methodName)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
