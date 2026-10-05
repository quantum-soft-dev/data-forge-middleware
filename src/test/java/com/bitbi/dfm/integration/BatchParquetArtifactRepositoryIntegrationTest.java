package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V49 manifest persistence, retry queue, exact lookup, and cleanup queries. */
@Transactional
class BatchParquetArtifactRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /**
     * The enqueue horizon (issue #380) these cases pass. Fixed rather than derived from the clock,
     * so the batches below — started on 2026-09-30 — stay inside it whenever the suite runs, while
     * the seeded store-01 batch of 2025 stays outside it.
     */
    private static final LocalDateTime HORIZON = LocalDateTime.of(2026, 9, 1, 0, 0);

    @Autowired
    private BatchParquetArtifactRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void claimsOnlyRetryableRowsAndResolvesExactManifest() {
        BatchParquetArtifact pending = repository.save(
                BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders"));
        BatchParquetArtifact failed = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "items");
        failed.markBuilding();
        failed.markFailed("retry me");
        repository.save(failed);
        BatchParquetArtifact ready = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "customers");
        ready.markBuilding();
        ready.markReady("egress/customers.parquet", 3, 100, "abc");
        repository.save(ready);

        List<UUID> claimed = repository.findNextRetryable(
                        LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600, 7, HORIZON, 500).stream()
                .map(BatchParquetArtifact::getId).toList();

        assertTrue(claimed.contains(pending.getId()), "a pending row is claimable");
        assertTrue(claimed.contains(failed.getId()), "a cooled-down failure is claimable");
        assertFalse(claimed.contains(ready.getId()), "a published row is never rebuilt");
        assertEquals(pending.getId(), repository.findBySiteIdAndBatchIdAndTableName(
                SITE_ID, BATCH_ID, "orders").orElseThrow().getId());
        assertTrue(repository.findBySiteIdAndBatchIdAndTableName(
                SITE_ID, BATCH_ID, "absent").isEmpty());
        assertEquals(BatchParquetArtifactStatus.READY,
                repository.findBySiteIdAndBatchIdAndTableName(
                        SITE_ID, BATCH_ID, "customers").orElseThrow().getStatus());
    }

    @Test
    void neverClaimsAnAbandonedArtifactAgain() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        artifact.markAbandoned("no declared schema");
        repository.save(artifact);

        assertFalse(isClaimed(artifact, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), 0, 0),
                "an artifact that used up its attempts is terminal, not retryable");
    }

    @Test
    void backsOffLongerAfterEachFailedAttempt() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        artifact.markBuilding();
        artifact.markBuilding();
        artifact.markFailed("s3 unavailable");
        repository.save(artifact);
        // attempt_count = 3 → the base delay is multiplied by 2^2.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(artifact, now, 60, 3600),
                "one base delay is not enough after three attempts");
        assertTrue(isClaimed(artifact, now.plusSeconds(4 * 60), 60, 3600),
                "the doubled delay has elapsed");
    }

    @Test
    void reclaimsAClaimWhoseBuildLeaseExpired() {
        BatchParquetArtifact stuck = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        stuck.markBuilding();
        repository.save(stuck);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(stuck, now, 60, 3600), "a live claim is left to its owner");
        assertTrue(isClaimed(stuck, now, 60, 0), "an expired lease makes the row claimable again");
    }

    @Test
    void activeClaimHidesPendingSiblingsOfTheSameBatch() {
        UUID batchId = BATCH_ID;
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, SITE_ID, "active-orders");
        building.markBuilding();
        repository.save(building);
        BatchParquetArtifact pending = repository.save(
                BatchParquetArtifact.pending(batchId, SITE_ID, "active-customers"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        List<UUID> liveLeaseCandidates = repository.findNextRetryable(now, 0, 3600, 7, HORIZON, 500)
                .stream().map(BatchParquetArtifact::getId).toList();
        List<UUID> expiredLeaseCandidates = repository.findNextRetryable(now, 0, 0, 7, HORIZON, 500)
                .stream().map(BatchParquetArtifact::getId).toList();

        assertFalse(liveLeaseCandidates.contains(pending.getId()),
                "a second worker must not split a batch while its sibling is building");
        assertTrue(expiredLeaseCandidates.contains(pending.getId()),
                "the batch becomes available again after the active lease expires");
    }

    @Test
    void catalogWatermarkAdvancesPastAPlantedFutureStamp() {
        jdbc.update("UPDATE batch_parquet_catalog_watermark SET published_at = '2099-01-01 00:00:00'");

        LocalDateTime first = repository.nextCatalogWatermark();
        LocalDateTime second = repository.nextCatalogWatermark();

        assertTrue(first.isAfter(LocalDateTime.of(2099, 1, 1, 0, 0)),
                "a lagging clock must not reuse or undercut the stored watermark: " + first);
        assertTrue(second.isAfter(first),
                "two ticks in one transaction must be strictly increasing: first="
                        + first + " second=" + second);
    }

    @Test
    void catalogPublishLockKeepsReadyAtAlignedWithCommitOrder() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LocalDateTime> first = executor.submit(() -> transaction.execute(status -> {
                repository.lockCatalogPublish();
                LocalDateTime stamped = repository.nextCatalogWatermark();
                holding.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return stamped;
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            Future<LocalDateTime> second = executor.submit(() -> transaction.execute(status -> {
                repository.lockCatalogPublish();
                return repository.nextCatalogWatermark();
            }));
            assertFalse(second.isDone(), "the second publisher must wait for the first commit");

            release.countDown();
            LocalDateTime firstReadyAt = first.get(10, TimeUnit.SECONDS);
            LocalDateTime secondReadyAt = second.get(10, TimeUnit.SECONDS);
            assertTrue(secondReadyAt.isAfter(firstReadyAt),
                    "a later commit must stamp a strictly later catalog watermark: first="
                            + firstReadyAt + " second=" + secondReadyAt);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void advisoryLockAllowsOnlyOneBatchClaimTransaction() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> owner = executor.submit(() -> transaction.execute(status -> {
                boolean acquired = repository.tryLockBatch(BATCH_ID);
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return acquired;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            Boolean competitor = transaction.execute(status -> repository.tryLockBatch(BATCH_ID));

            assertFalse(Boolean.TRUE.equals(competitor));
            release.countDown();
            assertTrue(owner.get(10, TimeUnit.SECONDS));
            assertTrue(Boolean.TRUE.equals(
                    transaction.execute(status -> repository.tryLockBatch(BATCH_ID))));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void settlesSpentExpiredClaimsAndLeavesRetryableClaimsVisible() {
        BatchParquetArtifact spent = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "spent");
        spent.markBuilding();
        repository.save(spent);
        BatchParquetArtifact retryable = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "retryable");
        repository.save(retryable);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertEquals(1, repository.abandonExpiredClaims(now, now, 0, 1, "lease expired"));

        assertEquals(BatchParquetArtifactStatus.ABANDONED,
                repository.findById(spent.getId()).orElseThrow().getStatus());
        assertTrue(repository.findNextRetryable(now, 0, 0, 2, HORIZON, 500).stream()
                .anyMatch(candidate -> candidate.getId().equals(retryable.getId())));
    }

    @Test
    void probesForSpentExpiredClaimsWithoutTouchingTheCatalogWatermark() {
        BatchParquetArtifact spent = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "spent");
        spent.markBuilding();
        repository.save(spent);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        String watermarkBefore = catalogWatermark();

        assertTrue(repository.hasSpentExpiredClaims(now, 0, 1),
                "a BUILDING row past its lease with its attempt budget spent is settleable");
        assertFalse(repository.hasSpentExpiredClaims(now, 86_400, 1),
                "a lease that has not elapsed yet leaves the row with its owner");
        assertFalse(repository.hasSpentExpiredClaims(now, 0, 1_000),
                "a row that still has attempts left is reclaimed by the claim query, not abandoned");
        assertEquals(watermarkBefore, catalogWatermark(),
                "the probe is a read: it must not move the catalog watermark");
    }

    @Test
    void theSettleProbeAndTheSettleUpdateSelectTheSameRows() {
        BatchParquetArtifact spent = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "spent");
        spent.markBuilding();
        repository.save(spent);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        // The probe is what decides whether the update ever runs, so a probe narrower than the
        // update settles nothing at all — and each query's own test would still be green.
        assertTrue(repository.hasSpentExpiredClaims(now, 0, 1));
        assertTrue(repository.abandonExpiredClaims(now, now, 0, 1, "lease expired") > 0,
                "the update must find what the probe promised");
        assertFalse(repository.hasSpentExpiredClaims(now, 0, 1),
                "and go quiet once the update has settled it");
        assertEquals(0, repository.abandonExpiredClaims(now, now, 0, 1, "lease expired"));
    }

    private String catalogWatermark() {
        return jdbc.queryForObject(
                "SELECT published_at::text FROM batch_parquet_catalog_watermark WHERE id = 1",
                String.class);
    }

    @Test
    void renewsALeaseOnlyForTheClaimThatStillHoldsTheRow() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        repository.save(artifact);
        UUID firstClaim = artifact.getClaimToken();
        LocalDateTime renewedAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);

        assertEquals(1, repository.touchClaim(artifact.getId(), firstClaim, renewedAt));
        assertFalse(isClaimed(artifact, renewedAt.plusSeconds(1), 60, 3600),
                "a renewed lease keeps the row with its owner");
        assertEquals(0, repository.touchClaim(artifact.getId(), UUID.randomUUID(), renewedAt),
                "a stale owner cannot renew a lease it no longer holds");
    }

    /**
     * Whether the claim query would hand this specific row out. The suite shares one database and
     * other classes leave manifest rows behind, so an assertion on the whole result set is not
     * this test's to make.
     */
    private boolean isClaimed(BatchParquetArtifact artifact, LocalDateTime now,
                              int retryDelaySeconds, int leaseSeconds) {
        return repository.findNextRetryable(now, retryDelaySeconds, leaseSeconds, 7, HORIZON, 500).stream()
                .anyMatch(claimed -> claimed.getId().equals(artifact.getId()));
    }

    // ---- Issue #378: the queue's head is per site, in batch order ----------------------------
    //
    // A later batch of a site must not be built — and therefore published to the Parquet Export
    // catalog — while any table of an earlier batch of that site is still unfinished. Batch order is
    // (batches.started_at, batches.id): a site has one active batch at a time, so the order sessions
    // started in is their seq order.

    /** A second site of the same account (test-data.sql), for the per-site half of the rule. */
    private static final UUID OTHER_SITE_ID = UUID.fromString("0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7");

    @Test
    void anEarlierBatchStillBuildingHoldsBackTheNextBatchOfItsSite() {
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact building = BatchParquetArtifact.pending(earlier, SITE_ID, "orders");
        building.markBuilding();
        repository.save(building);
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(next, now, 0, 3600),
                "the delta must not be built while the snapshot before it is still building");

        building.markReady("egress/orders.parquet", 3, 100, "abc");
        repository.save(building);

        assertTrue(isClaimed(next, now, 0, 3600),
                "once every table of the earlier batch is published the next batch is the head");
    }

    @Test
    void anEarlierBatchWaitingOutItsBackoffHoldsBackTheNextBatchOfItsSite() {
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact failed = BatchParquetArtifact.pending(earlier, SITE_ID, "orders");
        failed.markBuilding();
        failed.markFailed("s3 unavailable");
        repository.save(failed);
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(failed, now, 3600, 3600), "the failure is still cooling down");
        assertFalse(isClaimed(next, now, 3600, 3600),
                "a later batch must not overtake an earlier one that is waiting to be retried");
        assertTrue(isClaimed(failed, now, 0, 3600),
                "the earlier batch itself is the head of its site and stays claimable");
        assertFalse(isClaimed(next, now, 0, 3600),
                "and the later one keeps waiting while the earlier one is retried");
    }

    @Test
    void anAbandonedEarlierBatchNoLongerHoldsTheSiteBack() {
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact abandoned = BatchParquetArtifact.pending(earlier, SITE_ID, "orders");
        abandoned.markBuilding();
        abandoned.markAbandoned("no declared schema");
        repository.save(abandoned);
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));

        assertTrue(isClaimed(next, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600),
                "ABANDONED is terminal: blocking behind it would stop the site for ever");
    }

    @Test
    void oneTableOfAnEarlierBatchStillUnfinishedHoldsTheWholeNextBatch() {
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact published = BatchParquetArtifact.pending(earlier, SITE_ID, "orders");
        published.markBuilding();
        published.markReady("egress/orders.parquet", 3, 100, "abc");
        repository.save(published);
        BatchParquetArtifact straggler = BatchParquetArtifact.pending(earlier, SITE_ID, "customers");
        straggler.markBuilding();
        repository.save(straggler);
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));

        assertFalse(isClaimed(next, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600),
                "the batch is the unit of order, not the table");
    }

    @Test
    void anotherSiteIsBuiltWhileTheFirstSiteIsHeldBack() {
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        UUID otherSiteBatch = insertBatch(OTHER_SITE_ID, LocalDateTime.of(2026, 9, 30, 12, 0));
        BatchParquetArtifact building = BatchParquetArtifact.pending(earlier, SITE_ID, "orders");
        building.markBuilding();
        repository.save(building);
        BatchParquetArtifact heldBack = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        BatchParquetArtifact otherSite = repository.save(
                BatchParquetArtifact.pending(otherSiteBatch, OTHER_SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(heldBack, now, 0, 3600));
        assertTrue(isClaimed(otherSite, now, 0, 3600),
                "the head of the queue is per site, not global");
    }

    @Test
    void aLaterBatchStillUnfinishedDoesNotHoldBackAnEarlierOne() {
        // The shape an admin requeue (039) or a lazy backfill leaves: the earlier batch becomes
        // PENDING again while its successor is also unfinished. The earlier batch is the head.
        UUID earlier = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact laterRow = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        BatchParquetArtifact earlierRow = repository.save(
                BatchParquetArtifact.pending(earlier, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertTrue(isClaimed(earlierRow, now, 0, 3600));
        assertFalse(isClaimed(laterRow, now, 0, 3600));
    }

    @Test
    void batchesStartedAtTheSameInstantAreOrderedByTheirId() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 30, 10, 0);
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000378");
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000378");
        insertBatch(high, SITE_ID, startedAt);
        insertBatch(low, SITE_ID, startedAt);
        BatchParquetArtifact highRow = repository.save(BatchParquetArtifact.pending(high, SITE_ID, "orders"));
        BatchParquetArtifact lowRow = repository.save(BatchParquetArtifact.pending(low, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertTrue(isClaimed(lowRow, now, 0, 3600), "a tie is broken deterministically");
        assertFalse(isClaimed(highRow, now, 0, 3600), "and never lets both through at once");
    }


    // ---- Issue #380: a completed batch whose enqueue was lost still holds its place ----------
    //
    // The work rows are created AFTER_COMMIT. A batch whose enqueue was lost (the process died in
    // that window, or the enqueue threw) has no row, so the #378 rule cannot see it. The claim
    // query therefore also holds a site back behind an earlier batch that is still owed its rows,
    // and the sweep finds exactly those batches — one predicate for both, inside one horizon.

    @Test
    void anEarlierCompletedBatchStillOwedItsRowsHoldsBackTheNextBatchOfItsSite() {
        UUID lost = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        insertSegment(SITE_ID, lost, false);
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(next, now, 0, 3600),
                "a later batch must not be built past an earlier one whose rows were never created");

        BatchParquetArtifact recovered = repository.save(BatchParquetArtifact.pending(lost, SITE_ID, "orders"));

        assertTrue(isClaimed(recovered, now, 0, 3600), "once enqueued, the earlier batch is the head");
        assertFalse(isClaimed(next, now, 0, 3600), "and the #378 rule keeps the later one waiting");

        recovered.markBuilding();
        recovered.markReady("egress/orders.parquet", 3, 100, "abc");
        repository.save(recovered);

        assertTrue(isClaimed(next, now, 0, 3600), "published in batch order");
    }

    @Test
    void anEarlierBatchThatSealedNothingDoesNotHoldTheSiteBack() {
        UUID empty = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));

        assertTrue(isClaimed(next, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600),
                "a session with no published segment has nothing to build, so nothing to wait for");
        assertFalse(awaitingEnqueue(HORIZON).contains(empty));
    }

    @Test
    void anEarlierBatchThatDidNotCompleteDoesNotHoldTheSiteBack() {
        // Only COMPLETED and COMPLETED_WITH_WARNINGS publish BatchCompletedEvent, so only they are
        // enqueued after commit; a timed-out or failed session never was, and is not owed rows.
        UUID notCompleted = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0), "NOT_COMPLETED");
        insertSegment(SITE_ID, notCompleted, false);
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));

        assertTrue(isClaimed(next, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600));
        assertFalse(awaitingEnqueue(HORIZON).contains(notCompleted));
    }

    @Test
    void aBatchCompletedWithWarningsIsOwedItsRowsLikeACompletedOne() {
        UUID warned = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0), "COMPLETED_WITH_WARNINGS");
        insertSegment(SITE_ID, warned, false);
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));

        assertFalse(isClaimed(next, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600));
        assertTrue(awaitingEnqueue(HORIZON).contains(warned));
    }

    @Test
    void anEarlierBatchStartedBeforeTheHorizonNeitherHoldsBackNorIsSwept() {
        // The horizon is shared: a batch the gate would wait on is always one the sweep enqueues,
        // so the gate cannot wait for ever; and history from before 036 is not enqueued wholesale.
        UUID old = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        insertSegment(SITE_ID, old, false);
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        LocalDateTime between = LocalDateTime.of(2026, 9, 30, 10, 30);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertTrue(repository.findNextRetryable(now, 0, 3600, 7, between, 500).stream()
                .anyMatch(candidate -> candidate.getId().equals(next.getId())));
        assertFalse(awaitingEnqueue(between).contains(old));
        assertTrue(awaitingEnqueue(HORIZON).contains(old));
    }

    @Test
    void theSweepFindsCompletedBatchesWithSegmentsAndNoRowsInBatchOrder() {
        UUID second = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        insertSegment(SITE_ID, second, false);
        UUID first = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        insertSegment(SITE_ID, first, false);
        UUID enqueued = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 9, 0));
        insertSegment(SITE_ID, enqueued, false);
        repository.save(BatchParquetArtifact.pending(enqueued, SITE_ID, "orders"));
        UUID provisionalOnly = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 8, 0));
        insertSegment(SITE_ID, provisionalOnly, true);

        List<UUID> found = awaitingEnqueue(HORIZON);

        assertTrue(found.indexOf(first) >= 0 && found.indexOf(first) < found.indexOf(second),
                "both lost batches are found, earliest first");
        assertFalse(found.contains(enqueued), "a batch with even one row is not owed its rows");
        assertFalse(found.contains(provisionalOnly), "a provisional segment is not published yet");
    }

    @Test
    void theSweepLeavesABatchAloneUntilItsOwnEnqueueHasHadTimeToRun() {
        UUID fresh = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        insertSegment(SITE_ID, fresh, false);
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 30, 10, 5);

        assertFalse(repository.findBatchesAwaitingEnqueue(HORIZON, completedAt, 500).contains(fresh),
                "the AFTER_COMMIT enqueue may still be running for a batch completed at the cutoff");
        assertTrue(repository.findBatchesAwaitingEnqueue(HORIZON, completedAt.plusSeconds(1), 500)
                .contains(fresh));
    }

    @Test
    void theGateAndTheSweepSelectTheSameBatches() {
        // One predicate text serves both. A gate wider than the sweep would wait for a batch nobody
        // enqueues; a sweep wider than the gate would publish a recovered batch after its successors.
        UUID owed = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 10, 0));
        insertSegment(SITE_ID, owed, false);
        UUID later = insertBatch(SITE_ID, LocalDateTime.of(2026, 9, 30, 11, 0));
        BatchParquetArtifact next = repository.save(BatchParquetArtifact.pending(later, SITE_ID, "orders"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertTrue(awaitingEnqueue(HORIZON).contains(owed));
        assertFalse(isClaimed(next, now, 0, 3600));

        repository.insertPendingIfAbsent(UUID.randomUUID(), owed, SITE_ID, "orders", now);

        assertFalse(awaitingEnqueue(HORIZON).contains(owed), "the sweep has nothing more to do");
        assertFalse(isClaimed(next, now, 0, 3600), "and the #378 rule takes over from the gate");
    }

    private List<UUID> awaitingEnqueue(LocalDateTime horizon) {
        return repository.findBatchesAwaitingEnqueue(horizon,
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 10_000);
    }

    private void insertSegment(UUID siteId, UUID batchId, boolean provisional) {
        // Both queue markers stamped, so the site-blind egress and delta-SQL queues never claim it.
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                    record_count, content_hash, s3_key, mode, plugin_sql_at, egress_at, provisional)
                VALUES (?, ?, ?, ?, ?, 1, 'hash', ?, 'DELTA',
                        CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS timestamp),
                        CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS timestamp), ?)
                """, UUID.randomUUID(), siteId, batchId, nextSeq, nextSeq,
                "issue-380/" + batchId + "/" + nextSeq + ".pb.gz", provisional);
        nextSeq++;
    }

    /** Distinct per insert, far above anything another class seeds: uk_segment_site_first_seq. */
    private long nextSeq = 380_000_000L;

    private UUID insertBatch(UUID siteId, LocalDateTime startedAt) {
        return insertBatch(UUID.randomUUID(), siteId, startedAt);
    }

    private UUID insertBatch(UUID siteId, LocalDateTime startedAt, String status) {
        return insertBatch(UUID.randomUUID(), siteId, startedAt, status);
    }

    private UUID insertBatch(UUID batchId, UUID siteId, LocalDateTime startedAt) {
        return insertBatch(batchId, siteId, startedAt, "COMPLETED");
    }

    private UUID insertBatch(UUID batchId, UUID siteId, LocalDateTime startedAt, String status) {
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at,
                                     session_mode)
                VALUES (?, 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', ?, ?, ?, 0, 0, false,
                        ?, ?, ?, 'DELTA')
                """, batchId, siteId, status, "issue-378/" + batchId + "/", startedAt, startedAt,
                startedAt.plusMinutes(5));
        return batchId;
    }

    @Test
    void insertPendingIfAbsentIsIdempotentUnderTheUniqueIndex() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        assertEquals(1, repository.insertPendingIfAbsent(
                UUID.randomUUID(), BATCH_ID, SITE_ID, "orders", now));
        // The second enqueue of the same batch/table must be absorbed, not blow up on
        // uk_batch_parquet_artifact — two download clicks or two replicas can race here.
        assertEquals(0, repository.insertPendingIfAbsent(
                UUID.randomUUID(), BATCH_ID, SITE_ID, "orders", now));
        assertEquals(BatchParquetArtifactStatus.PENDING, repository
                .findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders").orElseThrow()
                .getStatus());
    }

    @Test
    void batchCleanupDeletesAllManifestRows() {
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders"));
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "items"));

        assertEquals(2, repository.deleteByBatchId(BATCH_ID));
        assertTrue(repository.findByBatchId(BATCH_ID).isEmpty());
    }
}
