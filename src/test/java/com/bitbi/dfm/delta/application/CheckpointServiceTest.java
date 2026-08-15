package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
    private final List<UploadedSnapshot> uploadedFrames = new ArrayList<>();
    private byte[] lastFrameBytes;

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final DeltaMetrics metrics = mock(DeltaMetrics.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    /**
     * The real guard over a mocked row: an unstubbed {@code findBySiteIdForUpdate} answers "no row",
     * i.e. generation 0 — the epoch every fixture here builds at.
     */
    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final CheckpointEpochGuard epochGuard = new CheckpointEpochGuard(syncStateRepository);

    private CheckpointService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE);
        when(metrics.timeCheckpoint(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(metrics.timeCheckpointPhase(any(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(metrics).timeCheckpointPhase(any(), any(Runnable.class));
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 0L, 1, false, false, 0L, 0L));

        ChangelogSegment segment = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/segment", "FULL_SNAPSHOT", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(segment));
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("customers", 2L, 2, "Bob")));
        when(checkpointRepository.findBySiteIdAndTableName(eq(SITE), any())).thenReturn(Optional.empty());
        when(checkpointStorage.uploadFrame(eq(SITE), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path file = invocation.getArgument(2);
                    lastFrameBytes = Files.readAllBytes(file);
                    return "checkpoints/frame-key";
                });
    }

    /**
     * The two scratch ceilings are separate keys because they fail differently (issue #138):
     * {@code maxTempBytes} bounds one table's snapshot and skips that table, while
     * {@code maxFrameTempBytes} bounds the all-tables reload frame and aborts the build.
     */
    private CheckpointService newService(String scratchDirectory, long maxTempBytes, long maxFrameTempBytes) {
        return new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher, epochGuard,
                scratchDirectory, maxTempBytes, maxFrameTempBytes);
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
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
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

        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
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
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.frameExists(SITE, 10L)).thenReturn(false);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(survivor));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void refoldsFromZeroWhenFrameAbsentButFullHistorySurvives() {
        // Frame gone but nothing was pruned (history still starts at seq 1): refold is lossless.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.frameExists(SITE, 2L)).thenReturn(false);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).downloadFrame(any(), anyLong());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
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
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(4L, 2L, 1, false, false, 0L, 0L));
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
    void writesTheFrameToDiskAndRemovesItAfterUploading() throws IOException {
        // The frame is a file handed to S3, not a collected List + gzip byte[] — and the file
        // is this build's litter the same way the snapshot is (issue #126).
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());
        recordFrameUploads();

        service.buildCheckpoint(SITE);

        assertEquals(1, uploadedFrames.size());
        assertTrue(uploadedFrames.get(0).sizeAtUpload() > 0, "the file must hold the frame at upload time");
        assertEquals(List.of(), snapshotsOnDisk(), "the temporary frame must not outlive the build");
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void removesTheTemporaryFrameWhenTheUploadFails() throws IOException {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());
        when(checkpointStorage.uploadFrame(eq(SITE), anyLong(), any(Path.class)))
                .thenThrow(new IllegalStateException("S3 refused the frame"));

        assertThrows(IllegalStateException.class, () -> service.buildCheckpoint(SITE));

        assertEquals(List.of(), snapshotsOnDisk(), "a failed frame upload must not leak its temporary file");
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void abortsTheBuildWhenTheFrameWouldCrossItsOwnCeiling() throws IOException {
        // The frame is the next build's seed: unlike a single oversized table it cannot be
        // skipped. Abort so the pointer stays put and the next run rewrites it. Since issue #138
        // the frame answers to max-frame-temp-bytes alone — a per-table ceiling wide enough for
        // every snapshot does not make the frame unbounded.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, 8L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        assertThrows(ArtifactSizeLimitExceededException.class, () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written frame must not be left behind");
    }

    @Test
    void anOverCeilingFrameCostsNoSnapshotUploadAtAll() throws IOException {
        // Issue #153. Crossing the frame ceiling is deterministic for the same fold, so the abort
        // repeats on every tick. Written after the snapshots it charged each of those repeats a
        // full set of per-table uploads at the *new* seq, leaving the previous seq's objects
        // unreferenced — and nothing but a site wipe sweeps `checkpoints/{siteId}/` (#118), so the
        // orphaned generations accumulated for as long as the site was left alone. Serializing the
        // frame first makes a failed build cost nothing durable.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, 8L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        assertThrows(ArtifactSizeLimitExceededException.class, () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written frame must not be left behind");
    }

    @Test
    void countsTheAbortSoTheFrozenPointerIsVisibleWithoutTheLogs() {
        // Issue #153. A per-table skip has had a counter since #113; the frame abort — which costs
        // the whole site its pointer, and with it retention — had only an ERROR line.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, 8L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        assertThrows(ArtifactSizeLimitExceededException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics).checkpointBuildAborted("frame_too_large");
        verify(metrics, never()).checkpointTableUnmaterialized(any());
    }

    @Test
    void doesNotCountAnAbortWhenTheFrameFailsForAnyOtherReason() {
        // The counter names one cause and must keep naming it: an S3 refusal is transient and the
        // next tick fixes it, while a deterministic over-ceiling frame is the thing an operator is
        // meant to be paged for.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());
        when(checkpointStorage.uploadFrame(eq(SITE), anyLong(), any(Path.class)))
                .thenThrow(new IllegalStateException("S3 refused the frame"));

        assertThrows(IllegalStateException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void writesTheFrameBeforeAnySnapshotOfTheSameBuild() {
        // The ordering is the fix, so it is asserted directly rather than only through its
        // consequence above: whatever ends the frame — the ceiling, an S3 refusal, an
        // unrenderable fold — must end it before a snapshot object exists at the new seq.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        InOrder order = inOrder(checkpointStorage);
        order.verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
        order.verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
    }

    @Test
    void stillKeepsOneCheckpointScratchFileOnDiskAtATime() {
        // Moving the frame first must not mean holding it open across the snapshot loop: the
        // deployed scratch budget (#131/#138) is `2 x max(table, frame)`, one file per build path,
        // not `frame + table`. So the frame is written, uploaded and deleted before the first
        // table's file is created.
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        recordFrameUploads();

        service.buildCheckpoint(SITE);

        assertEquals(1, uploadedFrames.size());
        assertEquals(1, uploadedFrames.get(0).snapshotsOnDisk(),
                "the frame must be the only checkpoint scratch file while it is being uploaded");
        assertEquals(2, uploaded.size());
        assertTrue(uploaded.stream().allMatch(snapshot -> snapshot.snapshotsOnDisk() == 1),
                "the frame's scratch file must be gone before the first table's is created");
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
        // every site alike. Counting it as a per-table skip would detach every last-good snapshot
        // while the pointer still advanced. Rematerialize can restore a per-table hole, but must
        // not be asked to do so after we threw away the last downloadable keys. Abort instead —
        // the pointer and keys stay put and the next run redoes the whole build. (A failure
        // during the write stays a skip: see skipsOnlyTheTableThatCrossesTheLocalFileCeiling.)
        Path readOnly = Files.createDirectory(tempDirectory.resolve("read-only"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                readOnly.toFile().setWritable(false) && !Files.isWritable(readOnly),
                "the filesystem must honour a read-only directory");
        service = newService(readOnly.toString(), Long.MAX_VALUE, Long.MAX_VALUE);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));

        assertThrows(UncheckedIOException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void skipsOnlyTheTableThatCrossesTheSnapshotCeilingAndStillWritesTheFrame() throws IOException {
        // A table too big for the configured ceiling is a fact about that table, so it keeps the
        // per-table skip — the build completes and the pointer advances, as with unrenderable data.
        // Since issue #138 the snapshot ceiling is the table's alone: crossing it must not take the
        // frame down with it, which is what lets an operator set this key below the scratch volume
        // so the application refuses before kubelet evicts the pod.
        service = newService(tempDirectory.toString(), 8L, Long.MAX_VALUE);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordFrameUploads();

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(metrics).checkpointTableUnmaterialized("parquet_failed");
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
        assertEquals(1, uploadedFrames.size(), "the frame is bounded by its own, wider ceiling");
        verify(syncStateService).recordCheckpoint(SITE, 2L);
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written file must not be left behind");
    }

    @Test
    void rematerializesDetachedParquetOnTheNextBuildWithoutNewSegments() {
        // Issue #128: a per-table upload failure detaches the snapshot and still advances the
        // pointer. The next scheduled build used to return as soon as it saw no new segments,
        // so a transient S3 error left the table undownloadable until fresh data arrived.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> firstSave = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(firstSave.capture());
        Checkpoint detached = firstSave.getValue();
        assertNull(detached.getS3KeyParquet());
        assertEquals(2L, detached.getSeq());
        verify(syncStateService).recordCheckpoint(SITE, 2L);
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, detached);
        recordUploads("checkpoints/recovered-parquet-key");
        clearInvocations(syncStateService, checkpointStorage, checkpointRepository, changelogSegmentService);

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
        ArgumentCaptor<Checkpoint> recovered = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(recovered.capture());
        assertEquals("checkpoints/recovered-parquet-key", recovered.getValue().getS3KeyParquet());
        assertEquals(2L, recovered.getValue().getSeq(), "rematerialize must not invent a new seq");
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any());
        verify(changelogSegmentService, never()).readRecords(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rematerializesOnlyTheTableThatStillHasNoArtifact() {
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any(Path.class)))
                .thenReturn("checkpoints/customers-ok");
        when(checkpointStorage.uploadParquet(eq(SITE), eq("orders"), anyLong(), any(Path.class)))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> firstSave = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository, times(2)).save(firstSave.capture());
        Checkpoint customers = firstSave.getAllValues().stream()
                .filter(c -> c.getTableName().equals("customers")).findFirst().orElseThrow();
        Checkpoint orders = firstSave.getAllValues().stream()
                .filter(c -> c.getTableName().equals("orders")).findFirst().orElseThrow();
        assertNotNull(customers.getS3KeyParquet());
        assertNull(orders.getS3KeyParquet());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, customers, orders);
        when(checkpointStorage.uploadParquet(eq(SITE), eq("orders"), eq(2L), any(Path.class)))
                .thenReturn("checkpoints/orders-recovered");
        clearInvocations(checkpointStorage, checkpointRepository);

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(eq(SITE), eq("customers"), anyLong(), any());
        verify(checkpointStorage).uploadParquet(eq(SITE), eq("orders"), eq(2L), any(Path.class));
        ArgumentCaptor<Checkpoint> recovered = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(recovered.capture());
        assertEquals(1, recovered.getAllValues().size(), "only the detached table is rewritten");
        assertEquals("orders", recovered.getValue().getTableName());
        assertEquals("checkpoints/orders-recovered", recovered.getValue().getS3KeyParquet());
    }

    @Test
    void doesNotRewriteHealthyCheckpointsWhenThereAreNoNewSegments() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, saved.getValue());
        clearInvocations(checkpointStorage, checkpointRepository, syncStateService);

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void forcedRebuildRematerializesFromTheFrameWhenThereAreNoNewSegments() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, saved.getValue());
        uploaded.clear();
        recordUploads("checkpoints/forced-rebuild-key");
        clearInvocations(checkpointStorage, checkpointRepository, syncStateService);

        service.rebuildFromFrame(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
        ArgumentCaptor<Checkpoint> rebuilt = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(rebuilt.capture());
        assertEquals("checkpoints/forced-rebuild-key", rebuilt.getValue().getS3KeyParquet());
        assertEquals(2L, rebuilt.getValue().getSeq());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void forcedRebuildKeepsThePreviousKeyWhenRematerializeFails() {
        // Same-seq detach is wrong: a failed PutObject leaves the last-good object at
        // checkpoints/{site}/{table}/seq={seq}/snapshot.parquet. Nulling the row would make
        // a healthy table undownloadable. Detach is only valid when seq moved.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, saved.getValue());
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));
        clearInvocations(checkpointRepository, syncStateService, eventPublisher);

        service.rebuildFromFrame(SITE);

        verify(checkpointRepository, never()).save(any());
        assertEquals("checkpoints/parquet-key", saved.getValue().getS3KeyParquet());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rematerializesDetachedParquetWhenAllSegmentsHaveBeenPruned() {
        // The frame is the seed. After a full prune (audit-window 0) there are no leftover
        // changelog rows, but rematerialize must still recover from the frame.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> firstSave = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(firstSave.capture());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, firstSave.getValue());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of());
        recordUploads("checkpoints/recovered-after-prune");
        clearInvocations(syncStateService, checkpointStorage, checkpointRepository, eventPublisher);

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
        ArgumentCaptor<Checkpoint> recovered = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(recovered.capture());
        assertEquals("checkpoints/recovered-after-prune", recovered.getValue().getS3KeyParquet());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refusesWhenFrameUnreadableAndNoSegmentsRemain() {
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.frameExists(SITE, 2L)).thenReturn(false);
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of());

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void rematerializeDoesNotMoveThePointerWhenTheCauseRemains() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 still down"));

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> firstSave = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(firstSave.capture());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));

        parkAtPointer(2L, lastFrameBytes, firstSave.getValue());
        clearInvocations(syncStateService);

        service.buildCheckpoint(SITE);

        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        assertNull(firstSave.getValue().getS3KeyParquet());
        assertEquals(2L, firstSave.getValue().getSeq());
    }

    @Test
    void discardsTheBuildWhenAWipeBumpedTheEpochMidBuild() {
        // Issue #136. The build read the pre-wipe epoch; by the time it writes, the wipe has
        // committed. Re-inserting the row and the pre-wipe pointer would make retention prune the
        // new epoch's segments as "below checkpoint" and strand the site on "refusing lossy refold".
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        wipedTo(1L);

        assertEquals(Map.of(), service.buildCheckpoint(SITE), "a discarded build folds to nothing");

        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
        // The frame object *is* written, because since issue #153 it precedes the snapshots and
        // the guard only speaks at the first row write. That is the same litter the discard has
        // always left — the snapshots this build had already uploaded (see
        // doesNotMistakeAWipeForAPerTableParquetFailure) — and the wipe's own cut-off (#122)
        // deliberately spares objects newer than its start, so re-running the wipe sweeps it. What
        // must not survive is anything that makes the *new* epoch read it: the pointer is 0 after a
        // wipe, so no later build looks for frame@2.
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
    }

    @Test
    void doesNotMistakeAWipeForAPerTableParquetFailure() {
        // The per-table catch exists so one unrenderable table cannot freeze the pointer. An epoch
        // change is the opposite: nothing about this build may be published, so it must escape the
        // catch instead of being counted as a skip and letting the next table try.
        when(changelogSegmentService.readRecords("s3/segment")).thenReturn(List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        wipedTo(1L);

        service.buildCheckpoint(SITE);

        verify(metrics, never()).checkpointTableUnmaterialized(any());
        assertEquals(1, uploaded.size(), "the build stops at the first table it cannot publish");
    }

    @Test
    void discardsARematerializeWhenAWipeBumpedTheEpoch() {
        // The idle passes (#128) write rows too, at the recorded pointer — a wipe must stop them
        // just as it stops an advancing build.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        Checkpoint detached = Checkpoint.create(SITE, "customers", 2L, 2L);
        parkAtPointer(2L, ChangelogCodec.serialize(List.of(record("customers", 1L, 1, "Ann"))), detached);
        recordUploads("checkpoints/recovered-key");
        wipedTo(1L);

        service.buildCheckpoint(SITE);

        // The key is attached to the in-memory row before the write is attempted; what matters is
        // that the write is refused, so the wiped site keeps no trace of the recovered snapshot.
        verify(syncStateRepository).findBySiteIdForUpdate(SITE);
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void discardsInsteadOfRaisingTheLossyRefoldAlarmWhenTheSiteWasWiped() {
        // Same surface state as refusesLossyRefoldWhenFrameUnreadableAndHistoryPruned — pointer at
        // 10, no frame, early segments gone — but here a wipe caused it, and reporting a routine
        // operator action as this subsystem's data-loss alarm would send someone hunting a
        // corruption that never happened.
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L, 0L))
                .thenReturn(new SyncStateView(0L, 0L, 0, true, false, 1L, 1L));
        when(checkpointStorage.frameExists(SITE, 10L)).thenReturn(false);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(survivor));

        assertEquals(Map.of(), service.buildCheckpoint(SITE));

        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
    }

    @Test
    void takesTheEpochLockBeforeWritingTheRowAndThePointer() {
        // The positive half: every write of a healthy build goes through the same row lock the
        // wipe holds, which is what makes the two orderings the only possible ones.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(syncStateRepository, times(2)).findBySiteIdForUpdate(SITE);
        verify(checkpointRepository).save(any());
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void discardsTheBuildWhenARebaselineBumpedTheBaselineEpochMidBuild() {
        // Issue #142. A FULL_SNAPSHOT SessionEnd deletes every checkpoint row and zeroes the pointer
        // exactly as a wipe does, but must leave the generation alone (035). Keyed on the generation
        // the guard saw nothing, this build's pointer came back at the pre-re-baseline seq — and the
        // next build then seeded from the discarded baseline's frame, resurrecting deleted rows into
        // every checkpoint Parquet, with no alarm anywhere.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        SiteSyncState rebaselined = SiteSyncState.initial(SITE);
        rebaselined.resetForRebaseline(0L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(rebaselined));

        assertEquals(Map.of(), service.buildCheckpoint(SITE), "a discarded build folds to nothing");

        assertEquals(0L, rebaselined.getGeneration(), "the re-baseline must not move the wire epoch");
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void readsTheEpochNoLaterThanTheDataItGuards() {
        // The guard only works if the epoch is read no *later* than the segments it will fold.
        // Reading the segments first left a window: a re-baseline committing between the two reads
        // gave the build the pre-reset segment list together with the *new* epoch, so every guarded
        // write compared equal and was approved — it folded the discarded baseline, uploaded a frame
        // at the old lastSeq and moved the pointer there. Exactly the resurrection the guard exists
        // to stop, arrived at through the guard.
        //
        // The reset is simulated as committing during the sync-state read: with the correct order it
        // lands before the segment list is taken (so the build sees the new, empty history), with the
        // wrong one after it (so the build folds the old history at the new epoch).
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        when(syncStateService.getSyncState(SITE)).thenAnswer(invocation -> {
            when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of());
            return new SyncStateView(0L, 0L, 1, false, false, 0L, 1L);
        });
        SiteSyncState rebaselined = SiteSyncState.initial(SITE);
        rebaselined.resetForRebaseline(0L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(rebaselined));

        assertEquals(Map.of(), service.buildCheckpoint(SITE));

        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointRepository, never()).save(any());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
    }

    @Test
    void publishesTheCheckpointEventWithTheEpochTheBuildFolded() {
        // Issue #142, part 2. The event is published after the guarded pointer write has committed,
        // so a wipe can commit in the gap. Carrying the build's own epoch is what lets the listener
        // tell "my checkpoint" from "a checkpoint of a history that no longer exists".
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 0L, 1, false, false, 3L, 5L));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        // Three wipes then two re-baselines: generation 3, baseline epoch 5. Both halves travel.
        SiteSyncState history = SiteSyncState.initial(SITE);
        for (int i = 0; i < 3; i++) {
            history.resetForWipe();
        }
        history.resetForRebaseline(0L);
        history.resetForRebaseline(0L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(history));

        service.buildCheckpoint(SITE);

        verify(eventPublisher).publishEvent(new CheckpointRecordedEvent(SITE, 2L, new SiteEpoch(3L, 5L)));
    }

    // --- helpers ---

    /** The site has been wiped {@code generation} times since this build read its sync state. */
    private void wipedTo(long generation) {
        SiteSyncState wiped = SiteSyncState.initial(SITE);
        for (long i = 0; i < generation; i++) {
            wiped.resetForWipe();
        }
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wiped));
    }

    /**
     * Park the site at an already-recorded checkpoint: the pointer matches the last segment,
     * the frame is readable, and the given rows are what {@code findBySiteId} returns. A build
     * in this state used to be a no-op because {@code newSegments} is empty.
     */
    private void parkAtPointer(long seq, byte[] frameBytes, Checkpoint... rows) {
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(seq, seq, 1, false, false, 0L, 0L));
        when(checkpointStorage.frameExists(SITE, seq)).thenReturn(true);
        when(checkpointStorage.downloadFrame(SITE, seq)).thenReturn(frameBytes);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(rows));
        for (Checkpoint row : rows) {
            when(checkpointRepository.findBySiteIdAndTableName(SITE, row.getTableName()))
                    .thenReturn(Optional.of(row));
        }
    }

    /** Stub the upload, recording what each call saw on disk before the file is cleaned up. */
    private void recordUploads(String s3Key) {
        when(checkpointStorage.uploadParquet(eq(SITE), any(), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path file = invocation.getArgument(3);
                    uploaded.add(new UploadedSnapshot(file, Files.size(file), snapshotsOnDisk().size()));
                    return s3Key;
                });
    }

    private void recordFrameUploads() {
        when(checkpointStorage.uploadFrame(eq(SITE), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path file = invocation.getArgument(2);
                    uploadedFrames.add(new UploadedSnapshot(file, Files.size(file), snapshotsOnDisk().size()));
                    return "checkpoints/frame-key";
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
