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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

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

    @org.junit.jupiter.api.io.TempDir
    Path tempDirectory;

    /** What one upload saw: the file it was handed, its size, and how many snapshots were on disk. */
    private record UploadedSnapshot(Path path, long sizeAtUpload, long snapshotsOnDisk) {
    }

    private final List<UploadedSnapshot> uploaded = new ArrayList<>();

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final DeltaMetrics metrics = mock(DeltaMetrics.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);

    private CheckpointService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher,
                tempDirectory.toString(), Long.MAX_VALUE);
        when(metrics.timeCheckpoint(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(metrics.timeCheckpointPhase(any(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(metrics).timeCheckpointPhase(any(), any(Runnable.class));
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
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
        assertEquals(1, uploaded.size(), "one snapshot uploaded");
        assertTrue(uploaded.get(0).sizeAtUpload() > 0, "the uploaded file must carry the snapshot");

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
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(byte[].class));
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
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(eq(SITE), eq("orders"), anyLong(), any());
        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), anyLong(), any());

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository, times(2)).save(saved.capture());
        Checkpoint orders = saved.getAllValues().stream()
                .filter(c -> c.getTableName().equals("orders")).findFirst().orElseThrow();
        assertNull(orders.getS3KeyParquet(), "failed parquet must not be attached");
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");

        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(byte[].class));
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void detachesThePreviousSnapshotWhenThisBuildMaterializesNothing() {
        // The row is reused across builds: seq and rowCount advance every time. If a build fails to
        // write Parquet, keeping the previous build's key would publish stale rows under the new
        // seq — the checkpoint would list as fresh, Bit BI would serve the old bytes, and the rows
        // between the two seqs would reach no consumer at all. The CSV used to mask this.
        Checkpoint existing = Checkpoint.create(SITE, "customers", 1L, 1L);
        existing.attachParquet("checkpoints/previous-parquet-key");
        when(checkpointRepository.findBySiteIdAndTableName(SITE, "customers")).thenReturn(Optional.of(existing));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        assertEquals(2L, saved.getValue().getSeq(), "the row still advances with the fold");
        assertNull(saved.getValue().getS3KeyParquet(),
                "a superseded snapshot must not stay attached to a newer seq");
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");
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

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(byte[].class));
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
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(byte[].class));
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void recordsFoldParquetAndUploadPhasesOnAFullBuild() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(metrics).timeCheckpointPhase(eq("fold"), any(Supplier.class));
        // The parquet phase times a void, file-backed write since #112 — the meter name and its
        // "parquet" tag are unchanged, only the timed shape is.
        verify(metrics).timeCheckpointPhase(eq("parquet"), any(Runnable.class));
        verify(metrics, atLeastOnce()).timeCheckpointPhase(eq("upload"), any(Runnable.class));
        verify(metrics, never()).timeCheckpointPhase(eq("download_frame"), any(Supplier.class));
    }

    @Test
    void recordsDownloadFramePhaseWhenASeedFrameExists() {
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(4L, 2L, 1, false, false, 0L));
        when(checkpointStorage.frameExists(SITE, 2L)).thenReturn(true);
        when(checkpointStorage.downloadFrame(SITE, 2L)).thenReturn(ChangelogCodec.serialize(List.of()));
        ChangelogSegment newer = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 3L, 4L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(newer));
        when(changelogSegmentService.readRecords("s3/tail")).thenReturn(List.of(
                record("customers", 3L, 3, "Cara"),
                record("customers", 4L, 4, "Dan")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(metrics).timeCheckpointPhase(eq("download_frame"), any(Supplier.class));
        verify(checkpointStorage).downloadFrame(SITE, 2L);
        verify(metrics).timeCheckpointPhase(eq("fold"), any(Supplier.class));
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

    @Test
    void writesTheSnapshotToDiskAndRemovesItAfterUploading() throws IOException {
        // The snapshot is a file handed to S3, not a byte[] held in heap — and the file is this
        // build's litter: leaving it behind fills the node one checkpoint cycle at a time.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        assertEquals(1, uploaded.size());
        assertTrue(uploaded.get(0).sizeAtUpload() > 0, "the file must hold the snapshot at upload time");
        assertEquals(List.of(), snapshotsOnDisk(), "the temporary snapshot must not outlive the build");
    }

    @Test
    void removesTheTemporarySnapshotWhenTheUploadFails() throws IOException {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), any(), anyLong(), any(Path.class)))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.buildCheckpoint(SITE);

        assertEquals(List.of(), snapshotsOnDisk(), "a failed table must not leak its temporary file");
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");
    }

    @Test
    void keepsOnlyOneTableSnapshotOnDiskAtATime() {
        // Table by table: fold rows -> file -> upload -> drop the file. Holding every table's
        // snapshot at once would put the peak back in proportion to the table count.
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        assertEquals(2, uploaded.size(), "both tables uploaded");
        assertTrue(uploaded.stream().allMatch(snapshot -> snapshot.snapshotsOnDisk() == 1),
                "at most one table snapshot may exist on disk at any moment");
    }

    @Test
    void abortsTheBuildWhenTheScratchDirectoryCannotHoldASnapshot() throws IOException {
        // An unusable scratch directory is not this table's data — it would hit every table of
        // every site alike. Counting it as a per-table skip would detach every snapshot key while
        // the pointer still advanced, and nothing could restore them: a build with no new segments
        // returns early, so even a forced rebuild is a no-op. Abort instead — the pointer stays put
        // and the next run redoes the whole build. (A failure during the write stays a skip: see
        // skipsOnlyTheTableThatCrossesTheLocalFileCeiling.)
        Path readOnly = Files.createDirectory(tempDirectory.resolve("read-only"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                readOnly.toFile().setWritable(false) && !Files.isWritable(readOnly),
                "the filesystem must honour a read-only directory");
        service = new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher,
                readOnly.toString(), Long.MAX_VALUE);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));

        assertThrows(UncheckedIOException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void skipsOnlyTheTableThatCrossesTheLocalFileCeiling() throws IOException {
        // A table too big for the configured ceiling is a fact about that table, so it keeps the
        // per-table skip — the build completes and the pointer advances, as with unrenderable data.
        service = new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher,
                tempDirectory.toString(), 8L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");
        verify(syncStateService).recordCheckpoint(SITE, 2L);
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written file must not be left behind");
    }

    // --- helpers ---

    /** Stub the upload, recording what each call saw on disk before the file is cleaned up. */
    private void recordUploads(String s3Key) {
        when(checkpointStorage.uploadParquet(eq(SITE), any(), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path file = invocation.getArgument(3);
                    uploaded.add(new UploadedSnapshot(file, Files.size(file), snapshotsOnDisk().size()));
                    return s3Key;
                });
    }

    private List<Path> snapshotsOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(tempDirectory)) {
            return files.toList();
        }
    }

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
