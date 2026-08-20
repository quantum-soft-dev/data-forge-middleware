package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PrunableSegmentView;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
    private final ChangelogRetentionService service = new ChangelogRetentionService(
            segmentRepository, objectDeleter, syncStateService,
            new DeltaMetrics(new SimpleMeterRegistry()), 0);

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void pruneIsNotTransactional() throws Exception {
        Method prune = ChangelogRetentionService.class.getMethod("prune", UUID.class);

        assertNull(prune.getAnnotation(Transactional.class),
                "prune must not pin a connection across the object deletes (issue #234)");
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
    void theObjectDeleteRunsAfterTheRowDeleteAndWithNoTransactionOpen() {
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        View processed = new View(UUID.randomUUID(), "delta/s/1.pb.gz", DONE, DONE);
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, 10L)).thenReturn(List.of(processed));
        when(segmentRepository.deleteByIdIfProcessed(processed.id())).thenReturn(1);
        when(objectDeleter.deleteObjects(anyList())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "the batched DeleteObjects must not run inside a transaction (issue #234)");
            return new S3FileStorageService.DeleteObjectsResult(1, List.of());
        });

        assertEquals(1, service.prune(SITE));

        // Row first, object after: a crash in between leaves an unreferenced object for the #158
        // orphan sweep, never a row whose object is gone.
        var order = inOrder(segmentRepository, objectDeleter);
        order.verify(segmentRepository).deleteByIdIfProcessed(processed.id());
        order.verify(objectDeleter).deleteObjects(List.of(processed.key()));
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
