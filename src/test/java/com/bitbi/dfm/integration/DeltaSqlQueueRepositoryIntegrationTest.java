package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2 (026-bitbi-delta-sql) — persistence semantics of the delta-SQL work queue
 * ({@code changelog_segments.plugin_sql_at}) and per-table plugin baselines
 * ({@code plugin_delta_baselines}).
 */
class DeltaSqlQueueRepositoryIntegrationTest extends BaseIntegrationTest {

    /** store-01.example.com (test-data.sql). */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-02.example.com: second site of the same account (test-data.sql). */
    private static final UUID OTHER_SITE = UUID.fromString("0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7");
    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** A COMPLETED batch of {@link #SITE} (test-data.sql). */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /**
     * A batch of {@link #OTHER_SITE} (test-data.sql). The batch has to follow the site: nothing in
     * {@code ChangelogSegment.create(siteId, batchId, ...)} requires the two to agree, and a segment
     * pairing one site with another site's batch is invisible to every cleanup that sweeps by
     * {@code site_id} while still holding {@code changelog_segments_batch_id_fkey} against that
     * batch's deletion (issue #226).
     */
    private static final UUID OTHER_BATCH = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678905");

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private PluginDeltaBaselineRepository baselineRepository;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ChangelogSegment seedSegment(UUID siteId, long firstSeq, long lastSeq) {
        ChangelogSegment segment = ChangelogSegment.create(
                siteId, batchOf(siteId), firstSeq, lastSeq, lastSeq - firstSeq + 1,
                "hash-" + firstSeq, "delta/" + siteId + "/segments/" + firstSeq + ".pb.gz",
                "DELTA", Map.of());
        return segmentRepository.save(segment);
    }

    /** The seeded batch belonging to {@code siteId} — see {@link #OTHER_BATCH}. */
    private static UUID batchOf(UUID siteId) {
        return OTHER_SITE.equals(siteId) ? OTHER_BATCH : BATCH;
    }

    @Test
    void newSegmentIsPendingPluginSqlAndMarkable() {
        ChangelogSegment segment = seedSegment(SITE, 1, 5);
        assertNull(segment.getPluginSqlAt(), "fresh segment must be pending plugin SQL");

        segment.markPluginSqlProcessed();
        segmentRepository.save(segment);

        ChangelogSegment reloaded = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1).orElseThrow();
        assertNotNull(reloaded.getPluginSqlAt());
    }

    @Test
    void findNextPendingPluginSqlReturnsPerSiteHeadInSeqOrder() {
        transactionTemplate.executeWithoutResult(tx -> {
            seedSegment(SITE, 10, 15);
            seedSegment(SITE, 1, 5);       // head of SITE
            seedSegment(OTHER_SITE, 7, 9); // head of OTHER_SITE

            ChangelogSegment processed = seedSegment(OTHER_SITE, 1, 3);
            processed.markPluginSqlProcessed();
            segmentRepository.save(processed);

            List<ChangelogSegment> claimed = segmentRepository.findNextPendingPluginSql(10, LocalDateTime.now(ZoneOffset.UTC));

            assertEquals(2, claimed.size(), "one head per site");
            assertTrue(claimed.stream().anyMatch(s -> s.getSiteId().equals(SITE) && s.getFirstSeq() == 1L));
            assertTrue(claimed.stream().anyMatch(s -> s.getSiteId().equals(OTHER_SITE) && s.getFirstSeq() == 7L));
        });
    }

    @Test
    void clearPluginSqlBySiteIdRequeuesOnlyThatSite() {
        ChangelogSegment mine = seedSegment(SITE, 20, 25);
        mine.markPluginSqlProcessed();
        segmentRepository.save(mine);

        ChangelogSegment other = seedSegment(OTHER_SITE, 30, 35);
        other.markPluginSqlProcessed();
        segmentRepository.save(other);

        segmentRepository.clearPluginSqlBySiteId(SITE);

        assertNull(segmentRepository.findBySiteIdAndFirstSeq(SITE, 20).orElseThrow().getPluginSqlAt());
        assertNotNull(segmentRepository.findBySiteIdAndFirstSeq(OTHER_SITE, 30).orElseThrow().getPluginSqlAt());
    }

    /**
     * Issue #243: the deferred head keeps its site's place in line (per-site seq order is a
     * contract) and stops being offered to every other site's detriment.
     */
    @Test
    void deferredHeadIsHeldOutOfTheQueueWithoutStallingOtherSites() {
        transactionTemplate.executeWithoutResult(tx -> {
            ChangelogSegment poison = seedSegment(SITE, 1, 5);
            seedSegment(SITE, 6, 9);
            seedSegment(OTHER_SITE, 1, 3);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            assertEquals(1, segmentRepository.deferPluginSql(poison.getId(), now.plusMinutes(5)));

            List<ChangelogSegment> claimed = segmentRepository.findNextPendingPluginSql(10, now);
            assertEquals(1, claimed.size(), "only the other site's head is claimable");
            assertEquals(OTHER_SITE, claimed.get(0).getSiteId());

            assertEquals(2, segmentRepository.findNextPendingPluginSql(10, now.plusMinutes(6)).size(),
                    "the cooldown ends and the segment is offered again — nothing is discarded");
            assertEquals(1, segmentRepository.findById(poison.getId()).orElseThrow().getPluginSqlAttempts());
        });
    }

    /** A plugin reinit is the operator saying the cause is gone: the cooldown goes with it (#243). */
    @Test
    void clearPluginSqlBySiteIdResetsTheRetryState() {
        transactionTemplate.executeWithoutResult(tx -> {
            ChangelogSegment poison = seedSegment(SITE, 40, 45);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            segmentRepository.deferPluginSql(poison.getId(), now.plusHours(1));

            segmentRepository.clearPluginSqlBySiteId(SITE);

            ChangelogSegment reloaded = segmentRepository.findById(poison.getId()).orElseThrow();
            assertEquals(0, reloaded.getPluginSqlAttempts());
            assertNull(reloaded.getPluginSqlRetryAt());
            assertFalse(segmentRepository.findNextPendingPluginSql(10, now).isEmpty(),
                    "claimable again at once, not after the cooldown its old failures earned");
        });
    }

    @Test
    void baselineCrudAndUniqueConstraint() {
        AccountPlugin activation = accountPluginRepository.save(AccountPlugin.activate(ACCOUNT, "bit-bi", Map.of()));

        baselineRepository.save(PluginDeltaBaseline.create(activation.getId(), SITE, "customers", 42L));
        baselineRepository.save(PluginDeltaBaseline.create(activation.getId(), SITE, "orders", 7L));

        Map<String, Long> seqs = baselineRepository.baselineSeqsBySiteId(SITE);
        assertEquals(2, seqs.size());
        assertEquals(42L, seqs.get("customers"));
        assertEquals(7L, seqs.get("orders"));

        assertThrows(DataIntegrityViolationException.class, () ->
                baselineRepository.save(PluginDeltaBaseline.create(activation.getId(), SITE, "customers", 99L)));
    }

    @Test
    void baselineSuspendAndRecapture() {
        AccountPlugin activation = accountPluginRepository.save(AccountPlugin.activate(ACCOUNT, "bit-bi", Map.of()));
        baselineRepository.save(PluginDeltaBaseline.create(activation.getId(), SITE, "customers", 42L));

        // suspend (FULL_SNAPSHOT semantics): raise to MAX_VALUE
        PluginDeltaBaseline row = baselineRepository.findByAccountPluginIdAndSiteId(activation.getId(), SITE)
                .get(0);
        row.suspend();
        baselineRepository.save(row);
        assertEquals(Long.MAX_VALUE, baselineRepository.baselineSeqsBySiteId(SITE).get("customers"));

        // recapture (reinit semantics): delete + reinsert
        baselineRepository.deleteByAccountPluginIdAndSiteId(activation.getId(), SITE);
        assertTrue(baselineRepository.baselineSeqsBySiteId(SITE).isEmpty());
        baselineRepository.save(PluginDeltaBaseline.create(activation.getId(), SITE, "customers", 100L));
        assertEquals(100L, baselineRepository.baselineSeqsBySiteId(SITE).get("customers"));
    }
}
