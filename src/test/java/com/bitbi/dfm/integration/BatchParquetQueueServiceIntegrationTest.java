package com.bitbi.dfm.integration;

import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.delta.application.BatchParquetQueueService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-transaction invariants for operator recovery of the completed-batch Parquet queue. */
class BatchParquetQueueServiceIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private BatchParquetQueueService service;

    @Autowired
    private BatchParquetArtifactRepository artifactRepository;

    @Autowired
    private AdminActionLogRepository auditRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void staleWorkerCannotRenewTheClaimAfterAConcurrentOperatorRequeue() throws Exception {
        Site site = siteRepository.findById(SITE_ID).orElseThrow();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "ops-race");
        artifact.markBuilding();
        ReflectionTestUtils.setField(artifact, "updatedAt",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        artifactRepository.save(artifact);
        UUID staleClaimToken = artifact.getClaimToken();

        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch requeuedUnderLock = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch staleTransactionStarted = new CountDownLatch(1);
        AtomicInteger staleBackendPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> operator = executor.submit(() -> requiresNew.executeWithoutResult(status -> {
                service.requeue(site, BATCH_ID, artifact.getId(), "127.0.0.1", "integration-test");
                requeuedUnderLock.countDown();
                awaitLatch(allowCommit);
            }));
            if (!requeuedUnderLock.await(10, TimeUnit.SECONDS)) {
                operator.get(1, TimeUnit.SECONDS);
                throw new AssertionError("operator did not reach the held-lock checkpoint");
            }

            Future<Integer> staleWorker = executor.submit(() -> requiresNew.execute(status -> {
                staleBackendPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                staleTransactionStarted.countDown();
                return artifactRepository.touchClaim(artifact.getId(), staleClaimToken,
                        LocalDateTime.now(ZoneOffset.UTC));
            }));
            assertTrue(staleTransactionStarted.await(10, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("Lock",
                    jdbcTemplate.queryForObject(
                            "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?",
                            String.class, staleBackendPid.get())));
            assertFalse(staleWorker.isDone(), "the worker update waits for the operator row lock");

            allowCommit.countDown();
            operator.get(10, TimeUnit.SECONDS);
            assertEquals(0, staleWorker.get(10, TimeUnit.SECONDS),
                    "the displaced claim token cannot renew the reset row");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }

        BatchParquetArtifact persisted = artifactRepository.findById(artifact.getId()).orElseThrow();
        assertEquals(BatchParquetArtifactStatus.PENDING, persisted.getStatus());
        assertEquals(0, persisted.getAttemptCount());
        assertFalse(persisted.isHeldBy(staleClaimToken));
        assertEquals(1, auditCount());
    }

    @Test
    void artifactResetAndAuditEntryRollBackTogether() {
        Site site = siteRepository.findById(SITE_ID).orElseThrow();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "ops-rollback");
        artifact.markBuilding();
        artifact.markAbandoned("attempts exhausted");
        artifactRepository.save(artifact);
        long auditRowsBefore = auditCount();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.requeue(site, BATCH_ID, artifact.getId(), "127.0.0.1", "integration-test");
            throw new IllegalStateException("force caller rollback");
        }));

        assertEquals(BatchParquetArtifactStatus.ABANDONED,
                artifactRepository.findById(artifact.getId()).orElseThrow().getStatus());
        assertEquals(auditRowsBefore, auditCount(),
                "the audit row must not survive a rolled-back artifact reset");
    }

    private long auditCount() {
        return auditRepository.findByActionType(AdminActionType.BATCH_PARQUET_REQUEUE,
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
