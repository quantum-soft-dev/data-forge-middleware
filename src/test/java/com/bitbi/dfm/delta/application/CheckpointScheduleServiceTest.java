package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CheckpointScheduleService} (issue #213).
 *
 * <p>The service exists so a surface can say <em>when</em> the checkpoint the site has not got yet
 * is due, instead of rendering the wait as a backlog. Everything here is about the two answers it
 * owes: the next occurrence of the configured cron, and "no answer" for a schedule that names no
 * occurrence at all.</p>
 */
@DisplayName("CheckpointScheduleService")
class CheckpointScheduleServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Kyiv");

    /** 2026-08-19 15:45 local — the QA run's "ten minutes after the snapshot" moment. */
    private static final Instant AFTERNOON =
            ZonedDateTime.of(2026, 8, 19, 15, 45, 0, 0, ZONE).toInstant();

    @Test
    @DisplayName("answers the next occurrence of the shipped nightly cron")
    void shouldAnswerNextNightlyOccurrence() {
        CheckpointScheduleService service = new CheckpointScheduleService("0 0 2 * * *", ZONE);

        Optional<Instant> next = service.nextBuildAt(AFTERNOON);

        assertThat(next).contains(ZonedDateTime.of(2026, 8, 20, 2, 0, 0, 0, ZONE).toInstant());
    }

    @Test
    @DisplayName("resolves the cron in the JVM's own zone, the zone @Scheduled uses")
    void shouldResolveInTheGivenZone() {
        ZoneId other = ZoneId.of("UTC");
        CheckpointScheduleService service = new CheckpointScheduleService("0 0 2 * * *", other);

        assertThat(service.nextBuildAt(AFTERNOON))
                .contains(ZonedDateTime.of(2026, 8, 20, 2, 0, 0, 0, other).toInstant());
    }

    @Test
    @DisplayName("follows a cron that is not the default")
    void shouldFollowAConfiguredCron() {
        CheckpointScheduleService service = new CheckpointScheduleService("0 30 * * * *", ZONE);

        assertThat(service.nextBuildAt(AFTERNOON))
                .contains(ZonedDateTime.of(2026, 8, 19, 16, 30, 0, 0, ZONE).toInstant());
    }

    @Test
    @DisplayName("answers nothing when the sweep is switched off")
    void shouldAnswerNothingWhenDisabled() {
        // "-" is Spring's own Scheduled.CRON_DISABLED: the tick never fires, so there is no next
        // build to promise, and the surface must fall back to saying only that none exists yet.
        assertThat(new CheckpointScheduleService("-", ZONE).nextBuildAt(AFTERNOON)).isEmpty();
        assertThat(new CheckpointScheduleService("   ", ZONE).nextBuildAt(AFTERNOON)).isEmpty();
        assertThat(new CheckpointScheduleService(null, ZONE).nextBuildAt(AFTERNOON)).isEmpty();
    }

    @Test
    @DisplayName("answers nothing rather than throwing on a cron it cannot parse")
    void shouldAnswerNothingOnAnUnparseableCron() {
        // Unreachable in a running application — Spring refuses to start with an invalid @Scheduled
        // cron — but this value is read on a request path, and a sync-state endpoint that 500s is a
        // worse failure than a missing "next build" line.
        CheckpointScheduleService service = new CheckpointScheduleService("every other tuesday", ZONE);

        assertThat(service.nextBuildAt(AFTERNOON)).isEmpty();
    }

    @Test
    @DisplayName("answers nothing for a schedule whose date never comes round")
    void shouldAnswerNothingWhenTheCronNeverMatches() {
        // 30 February parses and never matches, so CronExpression#next gives up and returns null.
        // Null is an answer this service must translate, not propagate as an NPE onto the endpoint.
        CheckpointScheduleService service = new CheckpointScheduleService("0 0 2 30 2 *", ZONE);

        assertThat(service.nextBuildAt(AFTERNOON)).isEmpty();
    }

    @Test
    @DisplayName("shares one schedule property with the scheduler that owns the tick")
    void shouldShareTheSchedulerProperty() {
        // The placeholder is a single constant referenced by both the @Scheduled annotation and
        // this service's @Value, so a changed cron cannot leave the UI promising the old hour.
        assertThat(CheckpointScheduler.CRON_PROPERTY).isEqualTo("${delta.checkpoint.cron:0 0 2 * * *}");
    }
}
