package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8.1 — {@code changelog_segments} as the durable egress work queue (V33, Delta Client v2 — 022).
 * {@code egress_at IS NULL} marks a pending segment; the picker returns only the per-site
 * <b>head</b> (lowest {@code first_seq}) so delta Parquet files publish in seq order per site,
 * while different sites drain in parallel ({@code FOR UPDATE SKIP LOCKED}).
 */
@Transactional
class DeltaEgressQueueIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE_A = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_A = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID SITE_B = UUID.fromString("0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7");
    private static final UUID BATCH_B = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678905");

    @Autowired
    private ChangelogSegmentRepository repository;

    private ChangelogSegment segment(UUID siteId, UUID batchId, long firstSeq, long lastSeq) {
        ChangelogSegment segment = ChangelogSegment.create(
                siteId, batchId, firstSeq, lastSeq, lastSeq - firstSeq + 1,
                "hash-" + firstSeq, "delta/" + siteId + "/" + firstSeq + ".pb.gz", "DELTA", null);
        return repository.save(segment);
    }

    @Test
    void returnsOnlyPerSiteHeadOfPendingSegments() {
        segment(SITE_A, BATCH_A, 1L, 5L);
        segment(SITE_A, BATCH_A, 6L, 9L);
        segment(SITE_B, BATCH_B, 1L, 3L);

        List<ChangelogSegment> pending = repository.findNextPendingEgress(10);

        Set<String> heads = pending.stream()
                .map(s -> s.getSiteId() + "@" + s.getFirstSeq())
                .collect(Collectors.toSet());
        assertEquals(Set.of(SITE_A + "@1", SITE_B + "@1"), heads);
    }

    @Test
    void advancesToNextSegmentAfterHeadIsEgressed() {
        ChangelogSegment head = segment(SITE_A, BATCH_A, 1L, 5L);
        segment(SITE_A, BATCH_A, 6L, 9L);

        head.markEgressed();
        repository.save(head);

        List<ChangelogSegment> pending = repository.findNextPendingEgress(10);

        assertEquals(1, pending.size());
        assertEquals(6L, pending.get(0).getFirstSeq());
        assertNotNull(head.getEgressAt());
    }

    @Test
    void excludesEgressedSegments() {
        ChangelogSegment only = segment(SITE_A, BATCH_A, 1L, 5L);
        only.markEgressed();
        repository.save(only);

        assertTrue(repository.findNextPendingEgress(10).isEmpty());
    }

    @Test
    void respectsLimit() {
        segment(SITE_A, BATCH_A, 1L, 5L);
        segment(SITE_B, BATCH_B, 1L, 3L);

        assertEquals(1, repository.findNextPendingEgress(1).size());
    }
}
