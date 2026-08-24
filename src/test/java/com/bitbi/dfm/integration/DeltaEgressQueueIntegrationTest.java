package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

    @PersistenceContext
    private EntityManager entityManager;

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

        List<ChangelogSegment> pending = repository.findNextPendingEgress(10, LocalDateTime.now(ZoneOffset.UTC));

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

        List<ChangelogSegment> pending = repository.findNextPendingEgress(10, LocalDateTime.now(ZoneOffset.UTC));

        assertEquals(1, pending.size());
        assertEquals(6L, pending.get(0).getFirstSeq());
        assertNotNull(head.getEgressAt());
    }

    @Test
    void excludesEgressedSegments() {
        ChangelogSegment only = segment(SITE_A, BATCH_A, 1L, 5L);
        only.markEgressed();
        repository.save(only);

        assertTrue(repository.findNextPendingEgress(10, LocalDateTime.now(ZoneOffset.UTC)).isEmpty());
    }

    @Test
    void respectsLimit() {
        segment(SITE_A, BATCH_A, 1L, 5L);
        segment(SITE_B, BATCH_B, 1L, 3L);

        assertEquals(1, repository.findNextPendingEgress(1, LocalDateTime.now(ZoneOffset.UTC)).size());
    }

    /**
     * Issue #243: a deferred head must keep its own site's order (nothing behind it may jump the
     * queue) while every other site drains — the whole point of the deferral being that one poison
     * segment cannot stall the global queue.
     */
    @Test
    void deferredHeadIsHeldOutOfTheQueueWithoutStallingOtherSites() {
        ChangelogSegment poison = segment(SITE_A, BATCH_A, 1L, 5L);
        segment(SITE_A, BATCH_A, 6L, 9L);
        segment(SITE_B, BATCH_B, 1L, 3L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        assertEquals(1, repository.deferEgress(poison.getId(), now.plusMinutes(5), 0));

        List<ChangelogSegment> pending = repository.findNextPendingEgress(10, now);
        assertEquals(Set.of(SITE_B + "@1"),
                pending.stream().map(s -> s.getSiteId() + "@" + s.getFirstSeq()).collect(Collectors.toSet()),
                "site A waits behind its own deferred head; site B drains");

        assertEquals(Set.of(SITE_A + "@1", SITE_B + "@1"),
                repository.findNextPendingEgress(10, now.plusMinutes(6)).stream()
                        .map(s -> s.getSiteId() + "@" + s.getFirstSeq()).collect(Collectors.toSet()),
                "the cooldown ends and the segment is offered again — nothing is discarded");

        ChangelogSegment reloaded = repository.findById(poison.getId()).orElseThrow();
        assertEquals(1, reloaded.getEgressAttempts());
    }

    /**
     * Issue #243, review round 2 / issue #245: the success path is a targeted
     * {@code UPDATE ... SET plugin_sql_at}/{@code egress_at}, so it cannot touch the retry columns.
     * This test pins the leftover hazard — a whole-entity save of the claim-time snapshot — which
     * without {@code updatable = false} on the retry columns would write the egress columns back
     * as they were then, erasing a deferral recorded in between and restarting its escalation from
     * zero. The retry state is deliberately outside the entity's own UPDATE while the bulk
     * statements still write it.
     */
    @Test
    void staleWholeEntitySaveDoesNotEraseTheOtherQueuesDeferral() {
        ChangelogSegment claimed = segment(SITE_A, BATCH_A, 1L, 5L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // the egress worker defers it while the delta-SQL worker still holds its claim-time snapshot
        repository.deferEgress(claimed.getId(), now.plusMinutes(4), 0);

        claimed.markPluginSqlProcessed();
        repository.save(claimed);

        // Read the row rather than the persistence context: merge copies the detached values onto
        // the managed instance whatever the column mapping says, and only the generated UPDATE
        // leaves them out — which is the property under test.
        entityManager.flush();
        entityManager.clear();
        ChangelogSegment reloaded = repository.findById(claimed.getId()).orElseThrow();
        assertEquals(1, reloaded.getEgressAttempts(), "the deferral survives the other queue's save");
        assertNotNull(reloaded.getEgressRetryAt());
        assertNotNull(reloaded.getPluginSqlAt(), "and the save it came with still landed");
        assertTrue(repository.findNextPendingEgress(10, now).isEmpty(),
                "still inside its cooldown rather than immediately claimable again");
    }

    /**
     * The deferral is claim-scoped (issue #243, review round 3): a straggler whose attempt started
     * before a peer's deferral — or before a reinit reset the site's retry state — must not undo
     * it.
     */
    @Test
    void doesNotDeferOnBehalfOfAClaimWhoseAttemptCountHasMoved() {
        ChangelogSegment poison = segment(SITE_A, BATCH_A, 1L, 5L);
        // Truncated to microseconds because that is what a PostgreSQL `timestamp` keeps: the JVM
        // clock is nanosecond-resolution on Linux (micros on macOS), so the round trip would
        // otherwise fail the equality below on CI and pass locally.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        assertEquals(1, repository.deferEgress(poison.getId(), now.plusMinutes(5), 0));

        assertEquals(0, repository.deferEgress(poison.getId(), now.plusMinutes(90), 0),
                "the second claim saw 0 attempts, the row is at 1 — its deferral is not this one's");

        entityManager.flush();
        entityManager.clear();
        ChangelogSegment reloaded = repository.findById(poison.getId()).orElseThrow();
        assertEquals(1, reloaded.getEgressAttempts());
        assertEquals(now.plusMinutes(5), reloaded.getEgressRetryAt());
    }

    /** A deferral is refused once the work landed (issue #243, the #212 marker-predicate shape). */
    @Test
    void doesNotDeferASegmentThatIsAlreadyEgressed() {
        ChangelogSegment done = segment(SITE_A, BATCH_A, 1L, 5L);
        done.markEgressed();
        repository.save(done);

        assertEquals(0, repository.deferEgress(done.getId(), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5), 0));
        assertEquals(0, repository.findById(done.getId()).orElseThrow().getEgressAttempts());
    }
}
