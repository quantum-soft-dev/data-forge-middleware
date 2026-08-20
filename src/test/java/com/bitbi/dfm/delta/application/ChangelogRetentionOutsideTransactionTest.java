package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PrunableSegmentView;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #234 — retention must not hold a HikariCP connection across its object deletes.
 *
 * <p>{@code prune} used to be {@code @Transactional} around the whole pass, so the batched
 * {@code DeleteObjects} round trip (and, before #212, one round trip per pruned segment) ran with
 * the pass's transaction and every row lock it had taken still open — on the nightly
 * {@code CheckpointScheduler} tick, per site, serially. The pass therefore opens no transaction of
 * its own: the projection read and each conditional row delete are the repository's own short
 * transactions, and the S3 half refuses to run when a transaction is already open rather than
 * silently reintroducing the hold (the #147 / #164 shape).</p>
 */
class ChangelogRetentionOutsideTransactionTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final LocalDateTime DONE = LocalDateTime.of(2026, 8, 1, 0, 0);

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final S3FileStorageService objectDeleter = mock(S3FileStorageService.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChangelogRetentionService service = new ChangelogRetentionService(
            segmentRepository, objectDeleter, syncStateService, new DeltaMetrics(registry), 0);

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void pruneIsNotTransactional() throws Exception {
        // Review round 1: reading only the method-level Spring annotation would stay green against
        // a class-level @Transactional (or the jakarta variant), which restores the exact hold this
        // ticket removes — and the runtime guard would then fire on every site of every tick, so
        // retention would stop entirely behind a per-site WARN from CheckpointScheduler's catch.
        Method prune = ChangelogRetentionService.class.getMethod("prune", UUID.class);

        for (AnnotatedElement element : List.of(prune, ChangelogRetentionService.class)) {
            assertNull(AnnotatedElementUtils.findMergedAnnotation(element, Transactional.class),
                    () -> "no Spring @Transactional may reach prune (issue #234): " + element);
            assertNull(AnnotatedElementUtils.findMergedAnnotation(element, jakarta.transaction.Transactional.class),
                    () -> "no jakarta @Transactional may reach prune (issue #234): " + element);
        }
    }

    @Test
    void pruneRefusesToRunInsideATransaction() {
        // A caller that wrapped the pass in a transaction would restore exactly the hold this
        // ticket removed — and silently, since every assertion about pruning still passes.
        TransactionSynchronizationManager.setActualTransactionActive(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.prune(SITE));

        assertTrue(thrown.getMessage().contains("transaction"), thrown.getMessage());
        verifyNoInteractions(syncStateService);
        verifyNoInteractions(segmentRepository);
        verifyNoInteractions(objectDeleter);
    }

    @Test
    void theObjectDeleteRunsOnlyAfterTheRowDeleteReportedSuccess() {
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View processed = new View(UUID.randomUUID(), "delta/s/1.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(processed));
        when(segmentRepository.deleteByIdIfProcessed(processed.id())).thenReturn(1);
        // Review round 2: asserting isActualTransactionActive() here would be vacuous — this
        // service is built with new(), the repository is a mock and there is no transaction
        // manager, so nothing in this class could make it fail. That property is pinned by
        // pruneIsNotTransactional above and, on the wired application, by
        // ChangelogRetentionIntegrationTest.theObjectDeleteRunsWithNoTransactionOpen.
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertEquals(1, service.prune(SITE));

        // Row first, object after: a crash in between leaves an unreferenced object for the #158
        // orphan sweep, never a row whose object is gone.
        var order = inOrder(segmentRepository, objectDeleter);
        order.verify(segmentRepository).deleteByIdIfProcessed(processed.id());
        order.verify(objectDeleter).deleteObjects(List.of(processed.key()));
    }

    @Test
    void anExceptionMidPassStillDeletesTheObjectsOfTheRowsAlreadyGone() {
        // Review round 1: with the pass no longer one transaction, a failure inside the loop (a
        // lock timeout on one row, a pool timeout, a failover) leaves every row deleted so far
        // committed — so the keys must go to S3 anyway, or the pass leaks exactly the objects the
        // row-first ordering exists to bound. The #158 sweep is the backstop, not the plan: it
        // ships dry-run by default, so its reclaim is inert until an operator turns it on.
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View first = new View(UUID.randomUUID(), "delta/s/1.pb.gz", DONE, DONE);
        View failing = new View(UUID.randomUUID(), "delta/s/2.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(first, failing));
        when(segmentRepository.deleteByIdIfProcessed(first.id())).thenReturn(1);
        when(segmentRepository.deleteByIdIfProcessed(failing.id()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertThrows(CannotAcquireLockException.class, () -> service.prune(SITE));

        verify(objectDeleter).deleteObjects(List.of(first.key()));
    }

    @Test
    void anExceptionMidPassStillReportsWhatThePassDidBeforeIt() {
        // Review round 2: durable partial progress needs durable accounting. The held-back
        // counters are the #212 stuck-backlog alarm and the INFO is the only record that rows
        // were pruned, so leaving them past the throw makes an aborted pass read as "nothing
        // happened" while rows and objects are gone.
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View pending = new View(UUID.randomUUID(), "delta/s/1.pb.gz", null, DONE);
        View failing = new View(UUID.randomUUID(), "delta/s/2.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(pending, failing));
        when(segmentRepository.deleteByIdIfProcessed(failing.id()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThrows(CannotAcquireLockException.class, () -> service.prune(SITE));

        assertEquals(1.0, registry.get("delta.retention.segments.held-back")
                        .tag("reason", "pending_plugin_sql").counter().count(),
                "the hold-back this pass observed is counted even though the pass aborted");
    }

    @Test
    void aFullChunkOfKeysIsDeletedDuringThePassRatherThanOnlyAtTheEnd() {
        // Review round 4: a pod kill between a row's commit and the end of the loop strands its
        // object, and the #158 sweep that would reclaim it ships dry-run — so the exposure is
        // bounded at one chunk. Free in round trips, since deleteObjects chunks at 1000 anyway.
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        List<PrunableSegmentView> views = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            views.add(new View(UUID.randomUUID(), "delta/s/" + i + ".pb.gz", DONE, DONE));
        }
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(views);
        when(segmentRepository.deleteByIdIfProcessed(any())).thenReturn(1);
        List<Integer> chunkSizes = new ArrayList<>();
        when(objectDeleter.deleteObjects(anyList())).thenAnswer(invocation -> {
            chunkSizes.add(((List<?>) invocation.getArgument(0)).size());
            return new S3FileStorageService.DeleteObjectsResult(1, List.of());
        });

        assertEquals(1001, service.prune(SITE));

        assertEquals(List.of(1000, 1), chunkSizes, "a full chunk goes out during the pass");
    }

    @Test
    void aBrokenMeterOnASuccessfulPassIsReportedWithoutFailingThePass() {
        // The decision moved across two rounds and the final shape is the synthesis. Round 4:
        // swallowing silently leaves the #212 alarm half-emitted with no error anywhere. Round 5:
        // rethrowing reaches CheckpointScheduler's catch, which logs "Checkpoint build/retention
        // failed" for a site whose checkpoint was built and whose rows were pruned — an operator
        // sent to a healthy site. So a reporting failure is logged as a reporting failure, and the
        // pass still returns what it did.
        DeltaMetrics brokenMetrics = mock(DeltaMetrics.class);
        doThrow(new IllegalStateException("meter conflict"))
                .when(brokenMetrics).retentionSegmentsHeldBack(any(), org.mockito.ArgumentMatchers.anyLong());
        ChangelogRetentionService withBrokenMetrics = new ChangelogRetentionService(
                segmentRepository, objectDeleter, syncStateService, brokenMetrics, 0);
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View processed = new View(UUID.randomUUID(), "delta/s/1.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(processed));
        when(segmentRepository.deleteByIdIfProcessed(processed.id())).thenReturn(1);
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertEquals(1, withBrokenMetrics.prune(SITE),
                "a broken meter is not this site's retention failure");
        verify(objectDeleter).deleteObjects(List.of(processed.key()));
    }

    @Test
    void aBrokenMeterDoesNotReplaceTheFailureThatEndedThePass() {
        DeltaMetrics brokenMetrics = mock(DeltaMetrics.class);
        doThrow(new IllegalStateException("meter conflict"))
                .when(brokenMetrics).retentionSegmentsHeldBack(any(), org.mockito.ArgumentMatchers.anyLong());
        ChangelogRetentionService withBrokenMetrics = new ChangelogRetentionService(
                segmentRepository, objectDeleter, syncStateService, brokenMetrics, 0);
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View failing = new View(UUID.randomUUID(), "delta/s/1.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(failing));
        when(segmentRepository.deleteByIdIfProcessed(failing.id()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThrows(CannotAcquireLockException.class, () -> withBrokenMetrics.prune(SITE));
    }

    private record View(UUID id, String key, LocalDateTime pluginSqlAt, LocalDateTime egressAt)
            implements PrunableSegmentView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getS3Key() {
            return key;
        }

        @Override
        public LocalDateTime getPluginSqlAt() {
            return pluginSqlAt;
        }

        @Override
        public LocalDateTime getEgressAt() {
            return egressAt;
        }
    }
}
