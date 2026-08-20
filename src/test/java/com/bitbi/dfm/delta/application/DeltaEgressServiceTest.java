package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A poison table (data that cannot be rendered against its declared schema) must not wedge the
 * egress queue: the render failure is scoped to that table, the remaining tables still publish,
 * and the segment is marked egressed — the skip-and-continue contract CheckpointService already
 * documents. Upload failures stay fatal (transient S3 errors must retry the segment).
 */
@ExtendWith(MockitoExtension.class)
class DeltaEgressServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();

    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private ChangelogSegmentService changelogSegmentService;
    @Mock
    private SiteSchemaService siteSchemaService;
    @Mock
    private S3CheckpointStorage storage;

    private SimpleMeterRegistry registry;
    private DeltaEgressService service;

    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new DeltaEgressService(segmentRepository, changelogSegmentService,
                siteSchemaService, storage, new DeltaMetrics(registry),
                new DeltaParquetProperties(8L * 1024 * 1024), 60, 7);
        segment = ChangelogSegment.create(SITE_ID, UUID.randomUUID(), 1L, 2L, 2L,
                "hash", "changelog/key", "DELTA", null);
    }

    @Test
    void shouldSkipPoisonTableAndStillEgressRemainingTables() {
        // "bad" renders first (stream order) and fails coercion: boolean column with garbage text.
        when(changelogSegmentService.readRecords("changelog/key")).thenReturn(List.of(
                insert("bad", 1L, Map.of("id", intVal(1), "flag", strVal("not-a-boolean"))),
                insert("good", 2L, Map.of("id", intVal(2)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "bad", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false),
                        new ColumnDefinition("flag", "boolean", false)), List.of("id"), List.of()),
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));

        service.egressSegment(segment);

        verify(storage).uploadDelta(eq(SITE_ID), eq("good"), eq(1L), eq(2L), any(byte[].class));
        verify(storage, never()).uploadDelta(eq(SITE_ID), eq("bad"), anyLong(), anyLong(), any(byte[].class));
        assertNotNull(segment.getEgressAt(), "segment leaves the pending queue despite the poison table");
        verify(segmentRepository).save(segment);
        assertEquals(1.0, registry.get("delta.egress.segments").counter().count());
        assertEquals(1L, phaseCount("download"));
        assertEquals(2L, phaseCount("write"), "poison render and the surviving table are each a write");
        assertEquals(1L, phaseCount("upload"));
        assertEquals(1L, phaseCount("total"));
    }

    @Test
    void shouldRecordEgressPhasesOnASuccessfulSegment() {
        when(changelogSegmentService.readRecords("changelog/key")).thenReturn(List.of(
                insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));

        service.egressSegment(segment);

        assertEquals(1L, phaseCount("download"));
        assertEquals(1L, phaseCount("write"));
        assertEquals(1L, phaseCount("upload"));
        assertEquals(1L, phaseCount("total"));
        assertEquals(1.0, registry.get("delta.egress.segments").counter().count());
    }

    @Test
    void shouldKeepSegmentPendingWhenUploadFails() {
        when(changelogSegmentService.readRecords("changelog/key")).thenReturn(List.of(
                insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        when(storage.uploadDelta(eq(SITE_ID), eq("good"), anyLong(), anyLong(), any(byte[].class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        assertThrows(RuntimeException.class, () -> service.egressSegment(segment),
                "transient upload failure must roll the segment back for the sweep");
        assertNull(segment.getEgressAt());
    }

    /**
     * Issue #243: the queue path swallows the failure into a durable deferral, so the drain moves
     * on to another site instead of ending on this segment for ever. The unit of work
     * ({@link DeltaEgressService#egressSegment}) still throws — that is what the deferral reads.
     */
    @Test
    void shouldDeferAFailingSegmentInsteadOfEndingTheDrain() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("changelog/key"))
                .thenThrow(new RuntimeException("object unreadable"));

        assertTrue(service.egressNextPending(), "the drain keeps going");

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(segmentRepository).deferEgress(eq(segment.getId()), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusSeconds(59)),
                "the first failure waits the base delay: " + retryAt.getValue());
        assertNull(segment.getEgressAt(), "the segment stays the durable queue entry");
        verify(segmentRepository, never()).save(any());
        assertEquals(1.0, registry.get("delta.egress.errors").counter().count());
        assertEquals(0.0, registry.get("delta.egress.segments.poisoned").counter().count());
    }

    /** A segment that keeps failing stops being an ordinary retry and says so (issue #243). */
    @Test
    void shouldReportASegmentPoisonedOnceItPassesTheAttemptThreshold() {
        ReflectionTestUtils.setField(segment, "egressAttempts", 6);
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("changelog/key"))
                .thenThrow(new RuntimeException("object unreadable"));

        assertTrue(service.egressNextPending());

        assertEquals(1.0, registry.get("delta.egress.segments.poisoned").counter().count());
        assertEquals(1.0, registry.get("delta.egress.errors").counter().count());
    }

    /** Both new series exist from startup, so an alert can predate the first failure. */
    @Test
    void shouldRegisterTheFailureSeriesAtZero() {
        assertEquals(0.0, registry.get("delta.egress.errors").counter().count());
        assertEquals(0.0, registry.get("delta.egress.segments.poisoned").counter().count());
    }

    private static ChangeRecord insert(String table, long seq, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(Op.INSERT).setSeq(seq)
                .putAllKey(Map.of("id", data.get("id"))).putAllData(data).build();
    }

    private long phaseCount(String phase) {
        return registry.get("delta.egress.duration").tag("phase", phase).timer().count();
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
