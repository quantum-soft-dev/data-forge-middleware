package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Answers when the scheduled checkpoint build next runs (issue #213).
 *
 * <p>{@link CheckpointScheduler#buildCheckpoints()} is the only producer of checkpoints apart from
 * an operator-forced rebuild, so a site that has just been ingested has no checkpoint until that
 * cron next fires — by design. Without this answer the sync-state projection could only report the
 * distance between the applied watermark and a checkpoint pointer of zero, which every lag surface
 * then rendered as a backlog alarm. The projection now carries the moment the wait ends, and the
 * surfaces say "no checkpoint yet, next build at ..." instead of "N records behind".</p>
 *
 * <p>The cron is resolved in the JVM's default zone, which is the zone {@code @Scheduled} itself
 * uses when the annotation names none — the answer therefore describes the tick this process will
 * actually run.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class CheckpointScheduleService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduleService.class);

    /** Parsed schedule, or null when the sweep is switched off or the expression is unusable. */
    private final CronExpression expression;
    private final ZoneId zone;

    // Explicit, because the package-private constructor below (a fixed zone, for tests) makes this
    // class ambiguous to constructor autowiring.
    @Autowired
    public CheckpointScheduleService(@Value(CheckpointScheduler.CRON_PROPERTY) String cron) {
        this(cron, ZoneId.systemDefault());
    }

    CheckpointScheduleService(String cron, ZoneId zone) {
        this.zone = zone;
        this.expression = parse(cron);
    }

    /**
     * When the scheduled checkpoint build next runs, counted from now.
     *
     * @return the next occurrence, or empty when the schedule names none
     */
    public Optional<Instant> nextBuildAt() {
        return nextBuildAt(Instant.now());
    }

    /**
     * When the scheduled checkpoint build next runs after the given instant.
     *
     * @param from instant to search from (exclusive)
     * @return the next occurrence, or empty when the sweep is disabled, the configured expression
     *         cannot be parsed, or it names no further occurrence
     */
    public Optional<Instant> nextBuildAt(Instant from) {
        if (expression == null) {
            return Optional.empty();
        }
        ZonedDateTime next = expression.next(from.atZone(zone));
        return next == null ? Optional.empty() : Optional.of(next.toInstant());
    }

    private static CronExpression parse(String cron) {
        String trimmed = cron == null ? "" : cron.trim();
        if (trimmed.isEmpty() || Scheduled.CRON_DISABLED.equals(trimmed)) {
            return null;
        }
        try {
            return CronExpression.parse(trimmed);
        } catch (IllegalArgumentException e) {
            // Unreachable in a running application — Spring refuses to start when the same
            // placeholder cannot be parsed for the @Scheduled tick — but this value is read on a
            // request path, and an endpoint that 500s would be a worse failure than a missing
            // "next build" line.
            log.warn("Cannot parse delta.checkpoint.cron '{}'; the sync-state projection will not "
                    + "name the next checkpoint build", trimmed, e);
            return null;
        }
    }
}
