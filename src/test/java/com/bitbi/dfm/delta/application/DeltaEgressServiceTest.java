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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Mock
    private DeltaMetrics metrics;

    private DeltaEgressService service;

    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        service = new DeltaEgressService(segmentRepository, changelogSegmentService,
                siteSchemaService, storage, metrics);
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
        verify(metrics).segmentEgressed();
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

    private static ChangeRecord insert(String table, long seq, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(Op.INSERT).setSeq(seq)
                .putAllKey(Map.of("id", data.get("id"))).putAllData(data).build();
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
