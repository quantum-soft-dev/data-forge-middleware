package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * B3 — checkpoint materialization writes the full typed Parquet snapshot per table (alongside the
 * legacy CSV) for tables with a declared schema, and attaches its S3 key to the checkpoint row.
 */
class CheckpointServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final DeltaMetrics metrics = mock(DeltaMetrics.class);

    private final CheckpointService service = new CheckpointService(
            segmentRepository, changelogSegmentService, checkpointRepository,
            syncStateService, checkpointStorage, siteSchemaService, metrics);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(metrics.timeCheckpoint(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 0L, 1, false));

        ChangelogSegment segment = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/segment", "FULL_SNAPSHOT", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("customers", 2L, 2, "Bob")));
        when(checkpointRepository.findBySiteIdAndTableName(eq(SITE), any())).thenReturn(Optional.empty());
        when(checkpointStorage.uploadCsv(eq(SITE), eq("customers"), anyLong(), any()))
                .thenReturn("checkpoints/csv-key");
    }

    @Test
    void attachesFullParquetSnapshotWhenTableSchemaDeclared() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenReturn("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<byte[]> parquet = ArgumentCaptor.forClass(byte[].class);
        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), parquet.capture());
        assertTrue(parquet.getValue().length > 0, "parquet bytes must be written");

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        assertEquals("checkpoints/parquet-key", saved.getValue().getS3KeyParquet());
        assertEquals("checkpoints/csv-key", saved.getValue().getS3KeyCsv());
    }

    @Test
    void skipsParquetWhenNoSchemaDeclaredButStillWritesCsv() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        assertNull(saved.getValue().getS3KeyParquet());
        assertEquals("checkpoints/csv-key", saved.getValue().getS3KeyCsv());
    }

    @Test
    void refusesLossyRefoldWhenFrameUnreadableAndHistoryPruned() {
        // Pointer advanced to 10, but the frame reads as absent (deleted, or an S3 HEAD denial
        // masquerading as absence) and segments below the checkpoint were pruned: a refold from
        // the surviving tail would silently publish a truncated checkpoint.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false));
        when(checkpointStorage.frameExists(SITE, 10L)).thenReturn(false);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(survivor));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void refoldsFromZeroWhenFrameAbsentButFullHistorySurvives() {
        // Frame gone but nothing was pruned (history still starts at seq 1): refold is lossless.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false));
        when(checkpointStorage.frameExists(SITE, 2L)).thenReturn(false);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).downloadFrame(any(), anyLong());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any());
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    // --- helpers ---

    private static TableSchema customersSchema() {
        return new TableSchema(
                List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                        new TableSchema.ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"),
                List.of());
    }

    private static ChangeRecord record(String table, long seq, long id, String name) {
        Value idVal = Value.newBuilder().setIntValue(id).build();
        Value nameVal = Value.newBuilder().setStringValue(name).build();
        return ChangeRecord.newBuilder()
                .setTable(table)
                .setOp(Op.INSERT)
                .setSeq(seq)
                .putKey("id", idVal)
                .putData("id", idVal)
                .putData("name", nameVal)
                .build();
    }
}
