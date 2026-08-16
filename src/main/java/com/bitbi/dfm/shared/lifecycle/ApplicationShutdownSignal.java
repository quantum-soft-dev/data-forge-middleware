package com.bitbi.dfm.shared.lifecycle;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Answers one question: has this application context begun to close (issue #149 / #162)?
 *
 * <p>Long-running background work needs it because Spring's shutdown order makes "the call failed"
 * ambiguous. {@code AbstractApplicationContext.doClose()} publishes {@link ContextClosedEvent}
 * first and only then destroys the singletons, so by the time {@code destroyBeans()} has closed the
 * {@code S3Client} and the {@code HikariDataSource} every subsequent call from a task that is still
 * running fails — with an exception indistinguishable from the resource genuinely being broken. A
 * task that records a durable verdict on that exception writes a conclusion about its data from a
 * fact about the process.</p>
 *
 * <p>The flag is therefore set <b>before</b> anything is closed, which is what makes it usable: a
 * failure seen while it is set may be the shutdown and must not be recorded, and a task that checks
 * it between units of work stops instead of failing its way to the end. It is one-way — a context
 * that has begun closing never reopens.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class ApplicationShutdownSignal implements ApplicationListener<ContextClosedEvent> {

    private volatile boolean shuttingDown;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        this.shuttingDown = true;
    }

    /**
     * Whether this context has started closing.
     *
     * @return {@code true} once {@link ContextClosedEvent} has been published, and forever after
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
