package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #164 — a HikariCP connection must not be held across the egress S3 download or the
 * per-table uploads. The worker therefore opens no transaction of its own: the pending-row
 * claim and the {@code egress_at} write are short repository transactions, and the S3 half
 * refuses to run when a transaction is already open rather than silently reintroducing the hold.
 */
class DeltaEgressOutsideTransactionTest {

    private static final UUID SITE = UUID.randomUUID();

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final S3CheckpointStorage storage = mock(S3CheckpointStorage.class);
    private DeltaEgressService service;
    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        service = new DeltaEgressService(segmentRepository, changelogSegmentService,
                siteSchemaService, storage, new DeltaMetrics(new SimpleMeterRegistry()),
                new DeltaParquetProperties(8L * 1024 * 1024), 60, 7,
                new ApplicationShutdownSignal());
        segment = ChangelogSegment.create(SITE, UUID.randomUUID(), 1L, 1L, 1L,
                "hash", "changelog/key", "DELTA", null);
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void egressNextPendingAndEgressSegmentAreNotTransactional() throws Exception {
        Method next = DeltaEgressService.class.getMethod("egressNextPending");
        Method one = DeltaEgressService.class.getMethod("egressSegment", ChangelogSegment.class);

        assertNull(next.getAnnotation(Transactional.class),
                "egressNextPending must not pin a connection across S3 (issue #164)");
        assertNull(one.getAnnotation(Transactional.class),
                "egressSegment must not pin a connection across S3 (issue #164)");
    }

    @Test
    void egressRefusesToTalkToS3InsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.egressSegment(segment));
        assertTrue(thrown.getMessage().contains("transaction"), thrown.getMessage());
        verifyNoInteractions(changelogSegmentService);
        verifyNoInteractions(storage);
        assertNull(segment.getEgressAt());
    }

    @Test
    void egressNextPendingDownloadsAndUploadsBeforeMarkingTheRow() {
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("changelog/key")).thenReturn(List.of(
                ChangeRecord.newBuilder().setTable("orders").setOp(Op.INSERT).setSeq(1L)
                        .putAllKey(Map.of("id", Value.newBuilder().setIntValue(1).build()))
                        .putAllData(Map.of("id", Value.newBuilder().setIntValue(1).build()))
                        .build()));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "orders", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));

        assertTrue(service.egressNextPending());

        var order = inOrder(changelogSegmentService, storage, segmentRepository);
        order.verify(segmentRepository).findNextPendingEgress(eq(1), any());
        order.verify(changelogSegmentService).readRecords("changelog/key");
        order.verify(storage).uploadDelta(eq(SITE), eq("orders"), eq(1L), eq(1L), any(byte[].class));
        order.verify(segmentRepository).save(segment);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void emptyQueueTouchesNoStorage() {
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of());

        assertFalse(service.egressNextPending());
        verifyNoInteractions(changelogSegmentService);
        verifyNoInteractions(storage);
    }
}
