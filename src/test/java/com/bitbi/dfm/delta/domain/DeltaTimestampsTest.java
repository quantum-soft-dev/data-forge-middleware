package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review r3 — delta timestamps must be UTC wall-clock: the presentation DTOs serialize every
 * {@code LocalDateTime} with {@code toInstant(ZoneOffset.UTC)}, so an entity stamping JVM-local
 * time on a non-UTC host would skew the frontend's lag/staleness math by the zone offset
 * (a freshly-synced site reading as stalled for hours, updatedAt in the future, DST jumps).
 */
class DeltaTimestampsTest {

    private static final Duration TOLERANCE = Duration.ofSeconds(5);

    @Test
    void siteSyncStateStampsUtc() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        assertUtc(state.getUpdatedAt());
    }

    @Test
    void changelogSegmentStampsUtc() {
        ChangelogSegment segment = ChangelogSegment.create(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/key", "DELTA", Map.of());
        assertUtc(segment.getCreatedAt());

        segment.markEgressed();
        assertUtc(segment.getEgressAt());
    }

    @Test
    void checkpointStampsUtc() {
        Checkpoint checkpoint = Checkpoint.create(UUID.randomUUID(), "customers", 1L, 1L);
        assertUtc(checkpoint.getCreatedAt());
        assertUtc(checkpoint.getUpdatedAt());
    }

    private static void assertUtc(LocalDateTime stamped) {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        Duration drift = Duration.between(stamped, utcNow).abs();
        assertTrue(drift.compareTo(TOLERANCE) < 0,
                "timestamp must be UTC wall-clock (drift " + drift + " vs UTC now — a JVM-local "
                        + "stamp on a non-UTC host skews every lag/staleness computation)");
    }
}
