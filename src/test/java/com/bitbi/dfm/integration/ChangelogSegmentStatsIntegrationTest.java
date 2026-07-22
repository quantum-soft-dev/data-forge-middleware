package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * T6.2 — per-table stats (JSONB) round-trip on {@code changelog_segments} (V32, Delta Client v2
 * — 022, batch history addition). Pre-existing rows (no stats) must load null-safe.
 */
class ChangelogSegmentStatsIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ChangelogSegmentRepository repository;

    @Test
    void persistsAndReloadsPerTableStats() {
        Map<String, TableChangeStats> stats = Map.of(
                "customers", new TableChangeStats(2, 1, 0),
                "orders", new TableChangeStats(0, 0, 3));

        ChangelogSegment segment = ChangelogSegment.create(
                SITE, BATCH, 1L, 5L, 5L, "hash", "delta/segment.pb.gz", "DELTA", stats);
        repository.save(segment);

        ChangelogSegment reloaded = repository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();

        assertEquals(new TableChangeStats(2, 1, 0), reloaded.getStats().get("customers"));
        assertEquals(new TableChangeStats(0, 0, 3), reloaded.getStats().get("orders"));
    }

    @Test
    void statsIsNullWhenNotProvided() {
        ChangelogSegment segment = ChangelogSegment.create(
                SITE, BATCH, 10L, 12L, 3L, "hash2", "delta/segment2.pb.gz", "DELTA", null);
        repository.save(segment);

        ChangelogSegment reloaded = repository.findBySiteIdAndFirstSeq(SITE, 10L).orElseThrow();

        assertNull(reloaded.getStats());
    }
}
