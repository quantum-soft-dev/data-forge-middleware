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
 * B3 — checkpoint materialization writes the full typed Parquet snapshot per table for tables with
 * a declared schema, and attaches its S3 key to the checkpoint row.
 *
 * <p>Since issue #113 Parquet is the <b>only</b> format the build produces: the gzipped CSV
 * snapshot that used to ship alongside it is gone, and with it the second full pass over the folded
 * state. A table with no declared schema therefore yields no artifact at all — that hole is
 * asserted here (and counted, so it is visible in production) rather than left silent.</p>
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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);

    private final CheckpointService service = new CheckpointService(
            segmentRepository, changelogSegmentService, checkpointRepository,
            syncStateService, checkpointStorage, siteSchemaService, metrics, eventPublisher);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(metrics.timeCheckpoint(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 0L, 1, false, false, 0L));

        ChangelogSegment segment = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/segment", "FULL_SNAPSHOT", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("customers", 2L, 2, "Bob")));
        when(checkpointRepository.findBySiteIdAndTableName(eq(SITE), any())).thenReturn(Optional.empty());
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
        assertNull(saved.getValue().getS3KeyCsv(), "the V2 build must not materialize CSV any more");
    }

    @Test
    void materializesNoCsvSnapshotAlongsideTheParquet() {
        // The point of #113: one pass, one buffer. Parquet is the only object the build uploads
        // per table, so the folded state is never copied into a second row representation.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenReturn("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any());
        verifyNoMoreInteractions(checkpointStorage);
    }

    @Test
    void leavesTableUnmaterializedAndCountsItWhenNoSchemaDeclared() {
        // Without a declared schema there is no Parquet — and since #113 no CSV to fall back on
        // either, so the table has no downloadable artifact this build. The build still completes
        // and the counter makes the hole visible instead of silent.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(metrics).checkpointTableUnmaterialized("no_schema");

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        assertNull(saved.getValue().getS3KeyParquet());
        assertNull(saved.getValue().getS3KeyCsv());

        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void parquetFailureForOneTableSkipsItButCompletesTheBuild() {
        // "orders" declares a date column whose folded value cannot be coerced: the Parquet write
        // throws. That must not roll back the whole build (checkpoint pointer frozen, retention
        // skipped, segments accumulating) — that one table goes unmaterialized, the rest proceeds.
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                orderRecord(2L, "not-a-date")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", ordersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenReturn("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(eq(SITE), eq("orders"), anyLong(), any());
        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), anyLong(), any());

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository, times(2)).save(saved.capture());
        Checkpoint orders = saved.getAllValues().stream()
                .filter(c -> c.getTableName().equals("orders")).findFirst().orElseThrow();
        assertNull(orders.getS3KeyParquet(), "failed parquet must not be attached");
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");

        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any());
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void refusesLossyRefoldWhenFrameUnreadableAndHistoryPruned() {
        // Pointer advanced to 10, but the frame reads as absent (deleted, or an S3 HEAD denial
        // masquerading as absence) and segments below the checkpoint were pruned: a refold from
        // the surviving tail would silently publish a truncated checkpoint.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L));
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
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L));
        when(checkpointStorage.frameExists(SITE, 2L)).thenReturn(false);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).downloadFrame(any(), anyLong());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any());
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void buildCheckpointDoesNotHoldATransactionAcrossS3RoundTrips() throws NoSuchMethodException {
        // Same contract as BatchParquetDownloadService (025-T3): the build downloads the frame,
        // every segment, and uploads Parquet/frame to S3 — holding a HikariCP connection across
        // those network calls pins it for the whole multi-minute build. Repository calls run in
        // their own short transactions; recordCheckpoint is transactional on its own.
        assertNull(CheckpointService.class
                        .getMethod("buildCheckpoint", UUID.class)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "buildCheckpoint must not open a transaction spanning S3 round-trips");
    }

    // --- helpers ---

    private static TableSchema customersSchema() {
        return new TableSchema(
                List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                        new TableSchema.ColumnDefinition("name", "varchar(255)", true)),
                List.of("id"),
                List.of());
    }

    private static TableSchema ordersSchema() {
        return new TableSchema(
                List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                        new TableSchema.ColumnDefinition("placed_on", "date", true)),
                List.of("id"),
                List.of());
    }

    private static ChangeRecord orderRecord(long seq, String placedOn) {
        Value idVal = Value.newBuilder().setIntValue(1).build();
        Value dateVal = Value.newBuilder().setStringValue(placedOn).build();
        return ChangeRecord.newBuilder()
                .setTable("orders")
                .setOp(Op.INSERT)
                .setSeq(seq)
                .putKey("id", idVal)
                .putData("id", idVal)
                .putData("placed_on", dateVal)
                .build();
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
