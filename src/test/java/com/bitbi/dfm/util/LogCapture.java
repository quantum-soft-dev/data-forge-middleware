package com.bitbi.dfm.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures the log a class emits, so a test can assert on observability the way it asserts on any
 * other output.
 *
 * <p>Attach in {@code @BeforeEach} and {@link #close()} in {@code @AfterEach} (or use it in a
 * try-with-resources); detaching matters because the appender would otherwise keep collecting
 * events from every later test sharing the JVM.</p>
 *
 * <pre>{@code
 * capture = LogCapture.attachTo(MyService.class);
 * ...
 * assertEquals(1, capture.messagesAt(Level.WARN).size());
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    /** Start capturing everything the given class logs. */
    public static LogCapture attachTo(Class<?> type) {
        ch.qos.logback.classic.Logger logger =
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    /** Every event captured so far, in order, at every level. */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** The events captured at exactly the given level — use when the assertion needs the throwable. */
    public List<ILoggingEvent> eventsAt(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    /** The formatted messages captured at exactly the given level. */
    public List<String> messagesAt(Level level) {
        return eventsAt(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The formatted messages at the given level containing {@code fragment}. */
    public List<String> messagesContaining(Level level, String fragment) {
        return messagesAt(level).stream().filter(message -> message.contains(fragment)).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
