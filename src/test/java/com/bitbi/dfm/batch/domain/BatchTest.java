package com.bitbi.dfm.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Batch aggregate (029: session activity tracking).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("Batch Unit Tests")
class BatchTest {

    @Test
    @DisplayName("Should have null lastActivityAt on creation (v1 batches never set it)")
    void shouldHaveNullLastActivityAtOnCreation() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        assertThat(batch.getLastActivityAt()).isNull();
    }

    @Test
    @DisplayName("Should set lastActivityAt to now on touchActivity")
    void shouldTouchActivityUpdateLastActivityAt() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        batch.touchActivity();

        assertThat(batch.getLastActivityAt())
                .isNotNull()
                .isAfter(before)
                .isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }

    @Test
    @DisplayName("Should advance lastActivityAt on repeated touches")
    void shouldAdvanceLastActivityAtOnRepeatedTouch() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        batch.touchActivity();
        LocalDateTime first = batch.getLastActivityAt();
        batch.touchActivity();

        assertThat(batch.getLastActivityAt()).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("Should expire by startedAt when there was never any session activity")
    void shouldExpireByStartedAtWithoutActivity() {
        Batch batch = startedMinutesAgo(90);

        assertThat(batch.isExpired(60)).isTrue();
    }

    @Test
    @DisplayName("Should stay alive past the timeout while session activity is fresh (029)")
    void shouldNotExpireWhenActivityIsFresh() {
        Batch batch = startedMinutesAgo(90);

        batch.touchActivity();

        assertThat(batch.isExpired(60)).isFalse();
    }

    /** Old-started IN_PROGRESS batch via the protected all-args constructor (same package). */
    @Test
    @DisplayName("Should start tracking delta totals at zero, with no seq range yet (issue #346)")
    void shouldStartTrackingDeltaTotalsAtZero() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "CONTINUOUS");

        assertThat(batch.tracksDeltaTotals()).isTrue();
        assertThat(batch.getTotalRecords()).isZero();
        assertThat(batch.getTableCount()).isZero();
        assertThat(batch.getTableStats()).isEmpty();
        assertThat(batch.getFirstSeq()).isNull();
        assertThat(batch.getLastSeq()).isNull();
    }

    @Test
    @DisplayName("Should add every recorded segment to the batch totals, merging tables (issue #346)")
    void shouldAccumulateRecordedSegmentsIntoTheBatchTotals() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "CONTINUOUS");

        batch.recordDeltaSegment(new BatchDeltaSegment(3L, 101L, 103L, Map.of(
                "orders", new BatchTableStats(2, 1, 0),
                "customers", new BatchTableStats(0, 0, 1))));
        // Recorded out of seq order on purpose: the range is min/max, not first/last recorded.
        batch.recordDeltaSegment(new BatchDeltaSegment(4L, 1L, 4L, Map.of(
                "orders", new BatchTableStats(1, 2, 1))));

        assertThat(batch.getTotalRecords()).isEqualTo(7L);
        assertThat(batch.getTableCount()).isEqualTo(2);
        assertThat(batch.getTableStats()).containsOnly(
                Map.entry("orders", new BatchTableStats(3, 3, 1)),
                Map.entry("customers", new BatchTableStats(0, 0, 1)));
        assertThat(batch.getFirstSeq()).isEqualTo(1L);
        assertThat(batch.getLastSeq()).isEqualTo(103L);
    }

    @Test
    @DisplayName("Should count a segment with no per-table stats in the records only (issue #346)")
    void shouldCountASegmentWithoutStatsInTheRecordsOnly() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "DELTA");

        batch.recordDeltaSegment(new BatchDeltaSegment(5L, 1L, 5L, null));

        assertThat(batch.getTotalRecords()).isEqualTo(5L);
        assertThat(batch.getTableCount()).isZero();
        assertThat(batch.getTableStats()).isEmpty();
        assertThat(batch.getFirstSeq()).isEqualTo(1L);
        assertThat(batch.getLastSeq()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Should leave an untracked batch untracked: a partial total would read as the whole (issue #346)")
    void shouldNotStartTrackingABatchThatWasNotTrackedFromItsStart() {
        // A row written before V58, or by a pre-V58 pod mid rolling deploy: its earlier segments
        // were never counted, so counting only the later ones would store a wrong total that reads
        // as authoritative. It stays on the segment read instead.
        Batch batch = startedMinutesAgo(1);

        batch.recordDeltaSegment(new BatchDeltaSegment(5L, 1L, 5L,
                Map.of("orders", new BatchTableStats(5, 0, 0))));

        assertThat(batch.tracksDeltaTotals()).isFalse();
        assertThat(batch.getTotalRecords()).isNull();
        assertThat(batch.getTableStats()).isNull();
        assertThat(batch.getFirstSeq()).isNull();
    }

    private static Batch startedMinutesAgo(int minutes) {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
        return new Batch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BatchStatus.IN_PROGRESS, "path/", 0, 0L, false, startedAt, null, startedAt);
    }
}
