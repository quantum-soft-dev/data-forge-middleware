package com.bitbi.dfm.shared.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The signal long-running background work reads to tell "this resource is broken" from "this
 * process is going away" (issues #149 / #162).
 */
class ApplicationShutdownSignalTest {

    private final ApplicationShutdownSignal signal = new ApplicationShutdownSignal();

    @Test
    void answersFalseWhileTheContextIsLive() {
        assertFalse(signal.isShuttingDown());
    }

    @Test
    void answersTrueOnceTheContextHasBegunToClose() {
        signal.onApplicationEvent(new ContextClosedEvent(mock(ConfigurableApplicationContext.class)));

        assertTrue(signal.isShuttingDown());
    }

    @Test
    void isSetBeforeTheSingletonsAreDestroyed() {
        // The whole point of listening for ContextClosedEvent rather than for a @PreDestroy of our
        // own: doClose() publishes the event first and destroys the beans afterwards, so the flag
        // is already true by the time the S3Client and the DataSource a running build is using are
        // closed. A destruction callback would answer "no" for exactly the window that matters.
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ApplicationShutdownSignal.class, () -> signal);
        context.registerBean(ShutdownWitness.class, () -> new ShutdownWitness(signal));
        context.refresh();
        ShutdownWitness witness = context.getBean(ShutdownWitness.class);

        context.close();

        assertTrue(witness.sawShutdown, "the signal must already be set when destruction starts");
    }

    /** A bean that records what the signal said while it was being destroyed. */
    static class ShutdownWitness implements AutoCloseable {

        private final ApplicationShutdownSignal signal;
        private boolean sawShutdown;

        ShutdownWitness(ApplicationShutdownSignal signal) {
            this.signal = signal;
        }

        @Override
        public void close() {
            this.sawShutdown = signal.isShuttingDown();
        }
    }
}
