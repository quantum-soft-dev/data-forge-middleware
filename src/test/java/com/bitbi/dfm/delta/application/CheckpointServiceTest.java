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
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.util.LogCapture;
import ch.qos.logback.classic.Level;
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
    /** Small enough to exhaust in a test, large enough that one failure is not the ceiling. */
    private static final int MAX_MATERIALIZE_ATTEMPTS = 3;

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
    /** The real signal, driven by {@link #shuttingDown} rather than by a context close. */
    private final ApplicationShutdownSignal shutdownSignal = new ApplicationShutdownSignal() {
        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }
    };

    /**
     * The real budget (issue #178), with no wait: a test that holds it wants the deferral now, and
     * every other test is the only build in the process and never contends for it.
     */
    private final CheckpointFoldBudget foldBudget =
            new CheckpointFoldBudget(shutdownSignal, metrics, 0L);

    private CheckpointService service;

    /** Flipped by a test to model {@code ContextClosedEvent} arriving mid-build. */
    private volatile boolean shuttingDown;

    /**
     * One list drives both segment reads (issue #212 review): the seq-range projection the build
     * decides from, and the entity load the fold takes — everything above the seed, which with
     * {@code afterSeq} 0 is the whole committed set. Stubbed together so a fixture cannot
     * desynchronize coverage from content.
     */
    private void stubSiteSegments(List<ChangelogSegment> segments) {
        when(segmentRepository.findSeqRangesBySiteIdOrderByFirstSeq(SITE))
                .thenReturn(segments.stream().map(CheckpointServiceTest::rangeOf).toList());
        when(segmentRepository.findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(eq(SITE), anyLong()))
                .thenAnswer(inv -> {
                    long afterSeq = inv.getArgument(1, Long.class);
                    return segments.stream().filter(s -> s.getFirstSeq() > afterSeq).toList();
                });
    }

    private static ChangelogSegmentRepository.SegmentSeqRange rangeOf(ChangelogSegment segment) {
        return new ChangelogSegmentRepository.SegmentSeqRange() {
            @Override
            public long getFirstSeq() {
                return segment.getFirstSeq();
            }

            @Override
            public long getLastSeq() {
                return segment.getLastSeq();
            }
        };
    }

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
        // S3 answers about the seed frame, and it answers one of three ways since issue #157.
        // "Not there" is the fixture default; a test that means "S3 refused to say" states it.
        when(checkpointStorage.framePresence(any(), anyLong())).thenReturn(ObjectPresence.ABSENT);

        ChangelogSegment segment = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/segment", "FULL_SNAPSHOT", Map.of());
        stubSiteSegments(List.of(segment));
        stubSegmentRecords("s3/segment", List.of(
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
        return newService(scratchDirectory, maxTempBytes, maxFrameTempBytes, Long.MAX_VALUE);
    }

    /** As above, plus the ceiling on the fold's own heap (issue #152). */
    private CheckpointService newService(String scratchDirectory, long maxTempBytes,
                                         long maxFrameTempBytes, long maxFoldBytes) {
        return newService(scratchDirectory, maxTempBytes, maxFrameTempBytes, maxFoldBytes,
                TestScratchLeases.unboundedBudget());
    }

    /** As above, with a scratch directory shared with other writers (issue #150). */
    private CheckpointService newService(String scratchDirectory, long maxTempBytes,
                                         long maxFrameTempBytes, long maxFoldBytes,
                                         ParquetScratchBudget scratchBudget) {
        return new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher, epochGuard,
                new CheckpointRetryProperties(MAX_MATERIALIZE_ATTEMPTS), shutdownSignal, foldBudget,
                scratchBudget, scratchDirectory, maxTempBytes, maxFrameTempBytes, maxFoldBytes);
    }

    /**
     * Serve a segment's records to a streaming reader, one at a time — the build never asks for
     * them as a list any more (issue #152).
     */
    private void stubSegmentRecords(String s3Key, List<ChangeRecord> records) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ChangeRecord> consumer = invocation.getArgument(1);
            records.forEach(consumer);
            return null;
        }).when(changelogSegmentService).forEachRecord(eq(s3Key), any());
    }

    /**
     * Serve the seed frame as a stream. A fresh stream per call: the build reads the object once,
     * and a shared, already-consumed stream would hide a second read rather than fail on it.
     */
    private void stubFrame(long seq, byte[] frameBytes) {
        when(checkpointStorage.openFrame(SITE, seq))
                .thenAnswer(invocation -> new java.io.ByteArrayInputStream(frameBytes));
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
        stubSegmentRecords("s3/segment", List.of(
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
    void doesNotDetachASnapshotWhenTheFailureIsTheProcessShuttingDown() {
        // Issue #162, folded into #149. Spring publishes ContextClosedEvent and then closes the
        // S3Client and the DataSource, so a build still running (the 02:00 cron on a slow site)
        // sees its next call fail for a reason that has nothing to do with this table. Recording
        // that as the table's verdict detaches a healthy snapshot on an advancing seq and 404s it
        // for Bit BI and Parquet Export until the next nightly rematerialize.
        Checkpoint existing = Checkpoint.create(SITE, "customers", 1L, 1L);
        existing.attachParquet("checkpoints/previous-parquet-key");
        when(checkpointRepository.findBySiteIdAndTableName(SITE, "customers")).thenReturn(Optional.of(existing));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenAnswer(invocation -> {
                    shuttingDown = true;
                    throw new IllegalStateException("Connection pool has been shut down");
                });

        assertEquals(Map.of(), service.buildCheckpoint(SITE),
                "a build that ends with the process publishes nothing");

        assertEquals("checkpoints/previous-parquet-key", existing.getS3KeyParquet(),
                "the last good snapshot survives a build that was only ending");
        verify(checkpointRepository, never()).save(any());
        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void attemptsNoFurtherTableOnceTheProcessIsShuttingDown() {
        // The corollary: the remaining tables are not tried either. Every one of them would fail
        // the same way, and each failure is another chance to write a verdict about the data from
        // a fact about the process.
        stubSegmentRecords("s3/segment", List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        shuttingDown = true;
        recordUploads("checkpoints/parquet-key");

        assertEquals(Map.of(), service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(checkpointRepository, never()).save(any());
        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(metrics, never()).checkpointBuildAborted(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void refusesLossyRefoldWhenFrameUnreadableAndHistoryPruned() {
        // Pointer advanced to 10, but the frame reads as absent (deleted, or an S3 HEAD denial
        // masquerading as absence) and segments below the checkpoint were pruned: a refold from
        // the surviving tail would silently publish a truncated checkpoint.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(survivor));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        // The other permanent freeze, and it must reach the same meter as the frame ceiling
        // (issue #153): the pointer is stuck, retention stops with it, and no amount of waiting
        // repairs either — an alert written on this counter would otherwise miss half of them.
        verify(metrics).checkpointBuildAborted("lossy_refold");
    }

    @Test
    void refoldsFromZeroWhenFrameAbsentButFullHistorySurvives() {
        // Frame gone but nothing was pruned (history still starts at seq 1): refold is lossless.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.ABSENT);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointStorage, never()).openFrame(any(), anyLong());
        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
        verify(syncStateService).recordCheckpoint(SITE, 2L);
    }

    @Test
    void recordsFoldParquetAndUploadPhasesOnAFullBuild() {
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(metrics).recordCheckpointPhase(eq("fold"), anyLong());
        // The parquet phase times a void, file-backed write since #112 — the meter name and its
        // "parquet" tag are unchanged, only the timed shape is.
        verify(metrics).timeCheckpointPhase(eq("parquet"), any(Runnable.class));
        verify(metrics, atLeastOnce()).timeCheckpointPhase(eq("upload"), any(Runnable.class));
        verify(metrics, never()).recordCheckpointPhase(eq("download_frame"), anyLong());
    }

    @Test
    void recordsDownloadFramePhaseWhenASeedFrameExists() {
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(4L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        stubFrame(2L, ChangelogCodec.serialize(List.of()));
        ChangelogSegment newer = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 3L, 4L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(newer));
        stubSegmentRecords("s3/tail", List.of(
                record("customers", 3L, 3, "Cara"),
                record("customers", 4L, 4, "Dan")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(metrics).recordCheckpointPhase(eq("download_frame"), anyLong());
        verify(checkpointStorage).openFrame(SITE, 2L);
        verify(metrics).recordCheckpointPhase(eq("fold"), anyLong());
    }

    @Test
    void seedsTheFoldByStreamingTheFrameRatherThanReadingItIntoHeap() throws Exception {
        // Issue #152: the frame used to arrive as one byte[] that ChangelogCodec.parse then expanded
        // into a List of every record in the site — a second and a third full copy of the site
        // before the fold even started. The storage port no longer offers the byte[] form at all,
        // which is the half of this a mock cannot show.
        assertThrows(NoSuchMethodException.class,
                () -> S3CheckpointStorage.class.getMethod("downloadFrame", UUID.class, long.class),
                "downloadFrame must be gone, not merely unused: it is the byte[] this ticket removes");

        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        stubFrame(2L, ChangelogCodec.serialize(List.of(record("customers", 1L, 1, "Ann"))));
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        Map<String, Map<String, ChangelogFold.FoldedRow>> state = service.buildCheckpoint(SITE);

        assertEquals(1, state.get("customers").size(), "the streamed frame must seed the fold");
        verify(checkpointStorage).openFrame(SITE, 2L);
    }

    @Test
    void foldsEachSegmentAsItStreamsInsteadOfCollectingThemFirst() {
        // The other list this ticket removes: every new segment's records used to be added to one
        // ArrayList before a single fold call, so a re-baseline (whose whole snapshot sits above the
        // pointer) held the entire site as records *and* as the fold it was about to become.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        Map<String, Map<String, ChangelogFold.FoldedRow>> state = service.buildCheckpoint(SITE);

        assertEquals(2, state.get("customers").size(), "both streamed records must be folded");
        verify(changelogSegmentService).forEachRecord(eq("s3/segment"), any());
    }

    @Test
    void abortsTheBuildWhenTheFoldOutgrowsItsHeapBudget() {
        // The refusal this ticket exists for (issue #152): on a pod with a 2-3Gi limit the fold is
        // what runs out first, and an OOMKill takes the whole process with it — in-flight ingest
        // included — where an abort costs one site's build and says why.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, 64L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));

        CheckpointService.FoldTooLargeException thrown = assertThrows(
                CheckpointService.FoldTooLargeException.class, () -> service.buildCheckpoint(SITE));

        assertTrue(thrown.getMessage().contains("delta.checkpoint.max-fold-bytes"),
                "the message must name the key an operator would raise: " + thrown.getMessage());
        // Counted with the other aborts that do not repair themselves (issue #153): the fold is
        // deterministic for the same history, so every following tick ends the same way, with the
        // pointer — and retention with it — frozen where it was.
        verify(metrics).checkpointBuildAborted("fold_too_large");
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void buildsNormallyWhileTheFoldFitsItsHeapBudget() {
        // The other side of the guard: a budget that the site fits under must not change anything
        // about the build, or the ceiling would be a second way to lose a checkpoint.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, 1024L * 1024L);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(syncStateService).recordCheckpoint(SITE, 2L);
        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void defersABuildWhoseFoldWouldRunBesideAnother() throws Exception {
        // Issue #178: the ceiling above bounds one fold, and until now nothing bounded two of them
        // in one JVM — the nightly sweep and a forced rebuild, at 45% of the budget each, crossed
        // nothing and still exhausted the heap. The second build now waits, and when the wait is
        // spent it is DEFERRED: nothing folded, nothing written, nothing concluded about the site.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        java.util.concurrent.CountDownLatch heldBySomeoneElse = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letGo = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> foldBudget.runExclusively(UUID.randomUUID(), () -> {
            heldBySomeoneElse.countDown();
            awaitLatch(letGo);
            return null;
        }));
        holder.start();
        assertTrue(heldBySomeoneElse.await(5, java.util.concurrent.TimeUnit.SECONDS));

        try {
            assertThrows(CheckpointFoldBudget.BuildDeferredException.class,
                    () -> service.buildCheckpoint(SITE));
        } finally {
            letGo.countDown();
            holder.join(5_000L);
        }

        // Its own meter, never the abort counter: #153's contract for that one is refusals that do
        // not repair themselves, and this one is repaired by the neighbouring build finishing.
        verify(metrics).checkpointBuildDeferred();
        verify(metrics, never()).checkpointBuildAborted(any());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointRepository, never()).save(any());
        // Review round 2: the site's state is read *inside* the exclusion, so a deferred build has
        // not read it at all. Read before the budget, it would be as stale as the wait is long —
        // and a rebuild parked behind the nightly sweep would fold a segment list that retention
        // had already deleted from S3 behind the advanced pointer.
        verify(syncStateService, never()).getSyncState(any());
        verify(segmentRepository, never()).findSeqRangesBySiteIdOrderByFirstSeq(any());
        verify(segmentRepository, never()).findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(any(), anyLong());
    }

    @Test
    void doesNotCountAWaitTheShutdownCutShortAsContention() throws Exception {
        // Review round 2. The wait ends itself when the context starts closing, and that arrives as
        // the same exception a spent wait does — but it is not contention, and counting it would
        // move the alerting series on every rollout that catches a build waiting. Same rule that
        // keeps the shutdown ending off every meter (issue #162).
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        CheckpointFoldBudget waitingBudget =
                new CheckpointFoldBudget(shutdownSignal, metrics, 3_600L);
        service = new CheckpointService(
                segmentRepository, changelogSegmentService, checkpointRepository,
                syncStateService, checkpointStorage, siteSchemaService, metrics,
                new DeltaParquetProperties(8L * 1024 * 1024), eventPublisher, epochGuard,
                new CheckpointRetryProperties(MAX_MATERIALIZE_ATTEMPTS), shutdownSignal,
                waitingBudget, TestScratchLeases.unboundedBudget(), tempDirectory.toString(),
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        java.util.concurrent.CountDownLatch heldBySomeoneElse = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letGo = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> waitingBudget.runExclusively(UUID.randomUUID(), () -> {
            heldBySomeoneElse.countDown();
            awaitLatch(letGo);
            return null;
        }));
        holder.start();
        assertTrue(heldBySomeoneElse.await(5, java.util.concurrent.TimeUnit.SECONDS));

        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() -> service.buildCheckpoint(SITE));
        waiter.setUncaughtExceptionHandler((thread, failure) -> thrown.set(failure));
        waiter.start();
        Thread.sleep(200L);
        shuttingDown = true;
        waiter.join(10_000L);
        letGo.countDown();
        holder.join(5_000L);

        assertEquals(CheckpointFoldBudget.BuildDeferredException.class, thrown.get().getClass());
        verify(metrics, never()).checkpointBuildDeferred();
        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void doesNotCountANonWaitingProbeAsContention() throws Exception {
        // Review round 3. Once the nightly sweep has spent its wait it visits every remaining site
        // with a single non-blocking probe, so counting those would put hundreds of increments on
        // delta.checkpoint.builds.deferred for one collision — and raising the wait, which is what
        // that meter's documentation prescribes, would be the wrong answer for every one of them.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        java.util.concurrent.CountDownLatch heldBySomeoneElse = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letGo = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> foldBudget.runExclusively(UUID.randomUUID(), () -> {
            heldBySomeoneElse.countDown();
            awaitLatch(letGo);
            return null;
        }));
        holder.start();
        assertTrue(heldBySomeoneElse.await(5, java.util.concurrent.TimeUnit.SECONDS));

        try {
            assertThrows(CheckpointFoldBudget.BuildDeferredException.class,
                    () -> service.buildCheckpoint(SITE, false));
        } finally {
            letGo.countDown();
            holder.join(5_000L);
        }

        verify(metrics, never()).checkpointBuildDeferred();
    }

    @Test
    void holdsTheFoldBudgetForTheWholeBuildNotJustTheFoldLoop() throws Exception {
        // The folded state is what writeSnapshots iterates, so the heap is retained until the last
        // table has been uploaded. Releasing at the end of the fold loop would let a second build
        // start against a heap the first one is still holding — the very overlap this closes.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        java.util.List<Boolean> budgetFreeDuringUpload = new ArrayList<>();
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    budgetFreeDuringUpload.add(budgetIsFree());
                    return "checkpoints/parquet-key";
                });

        service.buildCheckpoint(SITE);

        assertEquals(List.of(false), budgetFreeDuringUpload,
                "the fold budget must still be held while the snapshots are uploaded");
    }

    /** Can another build take the fold budget right now? Asked from a thread that does not hold it. */
    private boolean budgetIsFree() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean free = new java.util.concurrent.atomic.AtomicBoolean();
        Thread probe = new Thread(() -> {
            try {
                foldBudget.runExclusively(UUID.randomUUID(), () -> {
                    free.set(true);
                    return null;
                });
            } catch (CheckpointFoldBudget.BuildDeferredException expected) {
                free.set(false);
            }
        });
        probe.start();
        probe.join(5_000L);
        return free.get();
    }

    private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void countsTheFoldOfTheSeedFrameAgainstTheSameBudget() {
        // A site that is quiet still folds its whole history from the frame, so the budget has to
        // cover the seed as well — otherwise the one build that reloads everything at once is the
        // one build the guard does not watch.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, 64L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        stubFrame(2L, ChangelogCodec.serialize(List.of(record("customers", 1L, 1, "Ann"))));
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));

        assertThrows(CheckpointService.FoldTooLargeException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics).checkpointBuildAborted("fold_too_large");
    }

    @Test
    void stillAttributesTheFrameTransferWhenTheFoldAbortsWhileReadingIt() {
        // The phases are recorded in a finally so an aborted build can be read at all; recording
        // download_frame=0 and charging the whole transfer to fold would make that reading wrong
        // on the one build worth reading.
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, 64L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        stubFrame(2L, ChangelogCodec.serialize(List.of(
                record("customers", 1L, 1, "Ann"), record("customers", 2L, 2, "Bob"))));
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));

        assertThrows(CheckpointService.FoldTooLargeException.class, () -> service.buildCheckpoint(SITE));

        ArgumentCaptor<Long> frameNanos = ArgumentCaptor.forClass(Long.class);
        verify(metrics).recordCheckpointPhase(eq("download_frame"), frameNanos.capture());
        assertTrue(frameNanos.getValue() > 0,
                "the transfer that did happen must not be charged to the fold: " + frameNanos.getValue());
    }

    @Test
    void warnsOnTheFoldsPeakRatherThanTheSizeItHappenedToEndAt() {
        // The ceiling is enforced on the running total, so the warning has to watch the same number.
        // A fold that rises and falls back — a night's inserts followed by the deletes that retire
        // them — would otherwise stay silent at DEBUG until the tick whose peak crosses the budget,
        // which is exactly the tick this warning exists to precede.
        List<ChangeRecord> records = List.of(
                record("customers", 1L, 1, "Ann"),
                record("customers", 2L, 2, "Bob"),
                record("customers", 3L, 3, "Cara"),
                deletion("customers", 4L, 2),
                deletion("customers", 5L, 3));
        // A budget the fold's peak fills past 75% and its final size does not.
        long peak = foldedBytes(records.subList(0, 3));
        long ending = foldedBytes(List.of(records.get(0)));
        long budget = peak + ending;
        assertTrue(peak * 100 >= budget * 75 && ending * 100 < budget * 75,
                "fixture: peak " + peak + " must be over and the ending " + ending
                        + " under 75% of " + budget);

        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, budget);
        ChangelogSegment segment = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 5L, 5L, "hash", "s3/segment", "DELTA", Map.of());
        stubSiteSegments(List.of(segment));
        stubSegmentRecords("s3/segment", records);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        List<String> warnings;
        try (LogCapture log = LogCapture.attachTo(CheckpointService.class)) {
            Map<String, Map<String, ChangelogFold.FoldedRow>> state = service.buildCheckpoint(SITE);
            assertEquals(1, state.get("customers").size(), "the deletes did shrink the fold back");
            warnings = log.messagesContaining(Level.WARN, "delta.checkpoint.max-fold-bytes");
        }

        assertEquals(1, warnings.size(),
                "a fold that peaked over the threshold must say so, whatever it shrank back to: "
                        + warnings);
    }

    /** What those records fold to, measured the same way the budget measures it. */
    private static long foldedBytes(List<ChangeRecord> records) {
        Map<String, Map<String, ChangelogFold.FoldedRow>> state = new java.util.LinkedHashMap<>();
        long bytes = 0;
        for (ChangeRecord record : records) {
            bytes += ChangelogFold.apply(state, record);
        }
        return bytes;
    }

    @Test
    void doesNotRecordAZeroFrameDownloadWhenTheObjectCannotEvenBeOpened() {
        // A GetObject that fails took time and must be sampled as such. Recording a zero would make
        // delta.checkpoint.duration{phase=download_frame} read as though frame downloads had got
        // faster during a read outage — and the outage puts every site of the tick in that bucket.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        when(checkpointStorage.openFrame(SITE, 2L)).thenThrow(
                new S3CheckpointStorage.CheckpointStorageException("GetObject failed", null));
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        ArgumentCaptor<Long> frameNanos = ArgumentCaptor.forClass(Long.class);
        verify(metrics).recordCheckpointPhase(eq("download_frame"), frameNanos.capture());
        assertTrue(frameNanos.getValue() > 0,
                "the failed GetObject must be sampled, not recorded as zero: " + frameNanos.getValue());
    }

    @Test
    void namesTheFrameWhenItsBodyCannotBeRead() {
        // Streaming moved the failure from S3CheckpointStorage.download — which named the object —
        // to ChangelogCodec.forEach, whose UncheckedIOException names neither the site nor the seq,
        // and CheckpointScheduler logs only the message.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        when(checkpointStorage.openFrame(SITE, 2L)).thenAnswer(invocation -> new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset mid-body");
            }
        });
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));

        S3CheckpointStorage.CheckpointStorageException thrown = assertThrows(
                S3CheckpointStorage.CheckpointStorageException.class, () -> service.buildCheckpoint(SITE));

        assertTrue(thrown.getMessage().contains(SITE.toString()) && thrown.getMessage().contains("seq 2"),
                "the failure must name the frame it could not read: " + thrown.getMessage());
    }

    @Test
    void namesTheSegmentWhenOneCannotBeRead() {
        // Same regression on the other half of the fold: readRecords used to fail as a
        // SegmentStorageException carrying the key, the streaming path as "Failed to stream change
        // records" carrying nothing.
        doThrow(new UncheckedIOException("Failed to stream change records", new IOException("reset")))
                .when(changelogSegmentService).forEachRecord(eq("s3/segment"), any());

        S3CheckpointStorage.CheckpointStorageException thrown = assertThrows(
                S3CheckpointStorage.CheckpointStorageException.class, () -> service.buildCheckpoint(SITE));

        assertTrue(thrown.getMessage().contains("s3/segment") && thrown.getMessage().contains(SITE.toString()),
                "the failure must name the segment and the site: " + thrown.getMessage());
    }

    @Test
    void recordsTheFoldsPeakSizeOnAMeterSoTheBandBelowTheCeilingIsAlertable() {
        // The 75% WARN precedes a permanent abort, and no alert can be written on a log line — the
        // same reasoning that put the abort itself on a counter (#153).
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Long> foldBytes = ArgumentCaptor.forClass(Long.class);
        verify(metrics).recordCheckpointFoldBytes(foldBytes.capture());
        assertTrue(foldBytes.getValue() > 0, "the fold's size must reach the meter: " + foldBytes.getValue());
    }

    @Test
    void recordsNoFoldSizeForAnIdleVisitOrAnAbortedBuild() {
        // An idle visit answers before folding (issue #149), so it has no size to report; an
        // aborted build has exactly one over-budget sample, which is the counter's business and
        // would poison the series an operator reads as "how much room is left".
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.PRESENT);
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of());

        service.buildCheckpoint(SITE);
        verify(metrics, never()).recordCheckpointFoldBytes(anyLong());

        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE, 64L);
        stubFrame(2L, ChangelogCodec.serialize(List.of(record("customers", 1L, 1, "Ann"))));
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                Checkpoint.create(SITE, "customers", 2L, 1L)));

        assertThrows(CheckpointService.FoldTooLargeException.class, () -> service.buildCheckpoint(SITE));
        verify(metrics, never()).recordCheckpointFoldBytes(anyLong());
    }

    @Test
    void resolvesAnUnsetFoldBudgetAgainstTheHeapTheProcessWasGiven() {
        // The budget has to arrive by itself on a pod nobody tuned — unlike the scratch ceilings,
        // whose disk the process cannot see, the heap is right there. Half rather than the quarter
        // capacity planning would ask for: this is the last line before an OOMKill and its refusal
        // is permanent, so it must not refuse a fold that fits (see resolveMaxFoldBytes).
        assertEquals(Runtime.getRuntime().maxMemory() / 2, CheckpointService.resolveMaxFoldBytes(0L),
                "an unset budget must derive from the max heap");
        assertEquals(123L, CheckpointService.resolveMaxFoldBytes(123L), "an explicit budget wins");
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
        stubSegmentRecords("s3/segment", List.of(
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
        stubSegmentRecords("s3/segment", List.of(
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
    void endsTheBuildWhenTheDirectoryHasNoRoomForATableSnapshot() throws IOException {
        // Issue #150, review round 2. A full directory is a SYSTEMIC scratch failure — every
        // remaining table would meet it too — so it ends the build, exactly as an unusable scratch
        // directory has since #112 and for the reason stated there. Skipping the one table looks
        // gentler and is not: see the two tests below for what it would have left behind.
        // Modelled as it happens: a completed-batch worker claims the directory in the moment
        // between the frame going to S3 and the first snapshot opening its file.
        ParquetScratchBudget shared = TestScratchLeases.budgetOf(4L * 1024 * 1024);
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, shared);
        ScratchLease neighbour = shared.open(ParquetScratchBudget.BATCH_ARTIFACT);
        when(siteSchemaService.getTableSchemas(SITE)).thenAnswer(invocation -> {
            neighbour.charge(4L * 1024 * 1024);
            return Map.of("customers", customersSchema());
        });
        recordFrameUploads();

        try {
            assertThrows(ScratchBudgetExceededException.class, () -> service.buildCheckpoint(SITE));
        } finally {
            neighbour.close();
        }

        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        // Not a verdict on the table: no unmaterialized count, no row written, no attempt spent.
        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(checkpointRepository, never()).save(any());
        // Nor a permanent abort: delta.checkpoint.builds.aborted means refusals that never repair
        // themselves (#153), and this one clears when the neighbouring writer finishes.
        verify(metrics, never()).checkpointBuildAborted(any());
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written file must not be left behind");
    }

    @Test
    void leavesThePointerWhereItWasWhenTheDirectoryRefusesATableSnapshot() throws IOException {
        // The reason ending the build beats skipping the table (issue #150, review round 2).
        // Skipping would advance last_checkpoint_seq with the table's row still at the old seq and
        // nothing marking it as owing a rewrite — the nightly rematerialize keys on a NULL
        // s3_key_parquet — so a site that then went quiet would serve a snapshot silently missing
        // every change in between, indefinitely, with retention having already pruned the segments
        // below the new pointer.
        Checkpoint existing = Checkpoint.create(SITE, "customers", 1L, 1L);
        existing.attachParquet("checkpoints/last-good.parquet");
        when(checkpointRepository.findBySiteIdAndTableName(SITE, "customers"))
                .thenReturn(Optional.of(existing));
        ParquetScratchBudget shared = TestScratchLeases.budgetOf(4L * 1024 * 1024);
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, shared);
        ScratchLease neighbour = shared.open(ParquetScratchBudget.BATCH_ARTIFACT);
        when(siteSchemaService.getTableSchemas(SITE)).thenAnswer(invocation -> {
            neighbour.charge(4L * 1024 * 1024);
            return Map.of("customers", customersSchema());
        });
        recordFrameUploads();

        try {
            assertThrows(ScratchBudgetExceededException.class, () -> service.buildCheckpoint(SITE));
        } finally {
            neighbour.close();
        }

        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        assertEquals("checkpoints/last-good.parquet", existing.getS3KeyParquet(),
                "a neighbour's disk use must not detach this table's snapshot");
        assertEquals(0, existing.materializeAttempts(),
                "nor spend an attempt against delta.checkpoint.max-materialize-attempts");
    }

    @Test
    void leavesNoCheckpointRowsBehindWhenASiteFirstBuildIsRefusedTheDirectory() throws IOException {
        // The sharper half of the same argument. findOrCreate's row for a table with no row yet is
        // not saved until a snapshot succeeds, so skipping every refused table on a site's FIRST
        // build would leave `checkpoints` empty with the pointer advanced — and
        // CheckpointFileQueryService reads "no checkpoint rows" as "not a Delta site yet" and hands
        // a Bit BI client the historical uploaded CSVs as if they were its current baseline.
        ParquetScratchBudget shared = TestScratchLeases.budgetOf(4L * 1024 * 1024);
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, shared);
        ScratchLease neighbour = shared.open(ParquetScratchBudget.BATCH_ARTIFACT);
        when(siteSchemaService.getTableSchemas(SITE)).thenAnswer(invocation -> {
            neighbour.charge(4L * 1024 * 1024);
            return Map.of("customers", customersSchema());
        });
        recordFrameUploads();

        try {
            assertThrows(ScratchBudgetExceededException.class, () -> service.buildCheckpoint(SITE));
        } finally {
            neighbour.close();
        }

        // The pointer not moving is what makes an empty `checkpoints` harmless here: the site is
        // exactly where it was, and the next tick redoes the whole seq.
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void releasesTheDirectoryItReservedWhenEachFileIsDone() {
        // A lease held past its file would shrink the directory for every later writer until the
        // pod restarted — the one way this budget could become the outage it prevents. The frame
        // and each snapshot are written one at a time, so a directory of exactly one reservation
        // chunk is enough for any number of builds; it is not enough for two if either leaks.
        ParquetScratchBudget narrow =
                TestScratchLeases.budgetOf(ParquetScratchBudget.CHUNK_BYTES);
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, narrow);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);
        service.buildCheckpoint(SITE);
        service.buildCheckpoint(SITE);

        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void endsTheBuildWithoutAnAbortCounterWhenTheFrameHasNoRoomInTheDirectory() throws IOException {
        // Issue #150. The frame's existing failure mode is that the build ends — it is the next
        // incremental seed and there is nothing to fall back on. What it must NOT do is join
        // delta.checkpoint.builds.aborted: every value there is permanent by contract (#153), and
        // an alert written on that meter must not fire for a collision that clears by itself.
        ParquetScratchBudget full = TestScratchLeases.budgetOf(4L);
        service = newService(tempDirectory.toString(), Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, full);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        assertThrows(ScratchBudgetExceededException.class, () -> service.buildCheckpoint(SITE));

        verify(metrics, never()).checkpointBuildAborted(any());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        assertEquals(List.of(), snapshotsOnDisk(), "the half-written frame must not be left behind");
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
        verify(changelogSegmentService, never()).forEachRecord(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rematerializesOnlyTheTableThatStillHasNoArtifact() {
        stubSegmentRecords("s3/segment", List.of(
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
    void anIdleVisitWithNothingToRematerializeCostsNoFrameDownloadOrFold() {
        // Issue #149. Since #137 a site is named by the tick because one of its rows is
        // unmaterialized, and the tick then visits it every night. Discovering there is nothing to
        // do must not cost a whole-site frame download plus a fold in heap: the probe that decides
        // it reads only the checkpoints table, so it belongs before the download, not after it.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());

        parkAtPointer(2L, lastFrameBytes, saved.getValue());
        clearInvocations(checkpointStorage, metrics);

        assertEquals(Map.of(), service.buildCheckpoint(SITE),
                "an idle visit with no work folds nothing at all");

        verify(checkpointStorage, never()).openFrame(any(), anyLong());
        verify(metrics, never()).recordCheckpointPhase(eq("download_frame"), anyLong());
        verify(metrics, never()).recordCheckpointPhase(eq("fold"), anyLong());
    }

    @Test
    void reapsACheckpointRowWhoseTableIsGoneFromTheFold() {
        // Issue #149, the state with no exit at all before this. The last row of "orders" was
        // DELETEd at the source, so the frame written by that build carries no "orders" record;
        // every later fold is therefore missing the table entirely, and both writeSnapshots and
        // rebuildFromFrame iterate the fold — the loop can never reach it. The row survived (only
        // a wipe or a re-baseline deletes checkpoint rows), kept its null key, and put the site on
        // the tick's work list every night for a table nothing could ever materialize. Not even a
        // forced rebuild helped: the only exit was manual SQL.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(saved.capture());

        Checkpoint vanished = Checkpoint.create(SITE, "orders", 2L, 0L);
        parkAtPointer(2L, lastFrameBytes, saved.getValue(), vanished);
        clearInvocations(checkpointRepository, checkpointStorage, syncStateService);

        service.buildCheckpoint(SITE);

        verify(checkpointRepository).deleteById(vanished.getId());
        verify(checkpointRepository, never()).save(any());
        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void spendsOneAttemptPerFailedRematerializeAndThenStopsRetrying() {
        // Issue #149, the rule. A table that came out of a build with no snapshot is retried by
        // every scheduled build (#128) and names its site on the tick's work list (#137). With
        // nothing bounding that, a deterministic cause — a schema the client never submits, a value
        // Parquet cannot render — cost a frame download, a whole-site fold and a per-table attempt
        // every night for the life of the site.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 2L, 1L);
        parkAtPointer(2L, frameOf(record("customers", 1L, 1, "Ann")), owing);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        for (int attempt = 1; attempt <= MAX_MATERIALIZE_ATTEMPTS; attempt++) {
            service.buildCheckpoint(SITE);
            assertEquals(attempt, owing.materializeAttempts(),
                    "each rematerialize that ends without a snapshot spends one attempt");
        }
        assertNotNull(owing.getLastMaterializeFailureAt(), "the last failure is dated");
        assertTrue(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));

        clearInvocations(checkpointRepository, checkpointStorage, metrics);

        service.buildCheckpoint(SITE);

        assertEquals(MAX_MATERIALIZE_ATTEMPTS, owing.materializeAttempts(), "the count stops there");
        verify(metrics, never()).checkpointTableUnmaterialized(any());
        verify(checkpointRepository, never()).save(any());
        // And the whole visit is skipped: with no retryable row left there is nothing for the fold
        // to be folded for, so the frame is not even fetched.
        verify(checkpointStorage, never()).openFrame(any(), anyLong());
    }

    @Test
    void aForcedRebuildRearmsATableThatHadGivenUp() {
        // Giving up is not a dead end, and the exit is not manual SQL: asking for a rebuild says
        // the cause has been dealt with — the schema was submitted, the value fixed — so the row
        // goes back into the nightly population. It re-arms whether or not the attempt succeeds,
        // because the operator's claim is about the cause, not about this one build.
        Checkpoint givenUp = Checkpoint.create(SITE, "customers", 2L, 1L);
        for (int i = 0; i < MAX_MATERIALIZE_ATTEMPTS; i++) {
            givenUp.recordFailedMaterialization();
        }
        parkAtPointer(2L, frameOf(record("customers", 1L, 1, "Ann")), givenUp);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/rearmed-key");

        service.rebuildFromFrame(SITE);

        assertEquals("checkpoints/rearmed-key", givenUp.getS3KeyParquet());
        assertEquals(0, givenUp.materializeAttempts(), "a snapshot clears the record of reaching it");
        assertNull(givenUp.getLastMaterializeFailureAt());
    }

    @Test
    void aForcedRebuildThatFailsStillLeavesTheRowRetryable() {
        Checkpoint givenUp = Checkpoint.create(SITE, "customers", 2L, 1L);
        for (int i = 0; i < MAX_MATERIALIZE_ATTEMPTS; i++) {
            givenUp.recordFailedMaterialization();
        }
        parkAtPointer(2L, frameOf(record("customers", 1L, 1, "Ann")), givenUp);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.rebuildFromFrame(SITE);

        assertEquals(1, givenUp.materializeAttempts(),
                "the forced attempt is the first of a fresh series, not the one past the ceiling");
        assertFalse(givenUp.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));
    }

    @Test
    void anIncrementalBuildStillWritesATableThatHadGivenUp() {
        // The cap bounds the dedicated retry, not materialization itself. A site with new segments
        // is visited for those segments and the build writes every table in its fold — the work is
        // happening regardless, so skipping this one would cost nothing and lose a chance.
        Checkpoint givenUp = Checkpoint.create(SITE, "customers", 1L, 1L);
        for (int i = 0; i < MAX_MATERIALIZE_ATTEMPTS; i++) {
            givenUp.recordFailedMaterialization();
        }
        when(checkpointRepository.findBySiteIdAndTableName(SITE, "customers"))
                .thenReturn(Optional.of(givenUp));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointStorage).uploadParquet(eq(SITE), eq("customers"), eq(2L), any(Path.class));
        assertEquals(0, givenUp.materializeAttempts());
    }

    @Test
    void doesNotSpendAnAttemptWhenAFailedRewriteLeavesTheLastGoodSnapshotInPlace() {
        // The retry exists for rows with nothing to serve. Charging an attempt to a row whose
        // last-good object is still downloadable would eventually retire a table nobody is
        // waiting on — and it is the same-seq case the detach rule already declines to touch.
        Checkpoint healthy = Checkpoint.create(SITE, "customers", 2L, 1L);
        healthy.attachParquet("checkpoints/last-good-key");
        parkAtPointer(2L, frameOf(record("customers", 1L, 1, "Ann")), healthy);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        when(checkpointStorage.uploadParquet(eq(SITE), eq("customers"), anyLong(), any()))
                .thenThrow(new IllegalStateException("S3 refused the snapshot"));

        service.rebuildFromFrame(SITE);

        assertEquals("checkpoints/last-good-key", healthy.getS3KeyParquet());
        assertEquals(0, healthy.materializeAttempts());
    }

    @Test
    void drainsTheNightlyAlarmOfASiteWhoseFrameAndChangelogAreBothGone() {
        // Round 2 of the #148 review, folded in here. With no segments at all historyPruned is
        // unconditionally true, so a missing frame raised "refusing lossy refold" — wrong in kind
        // (there is no history to refold, lossily or otherwise) and with no exit but manual SQL, a
        // wipe or a re-baseline. The site is on the tick's work list only because of its
        // unmaterialized rows, so charging the abort to them is what ends the nightly alarm.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 2L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.ABSENT);
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        for (int attempt = 1; attempt <= MAX_MATERIALIZE_ATTEMPTS; attempt++) {
            assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                    () -> service.buildCheckpoint(SITE));
            assertEquals(attempt, owing.materializeAttempts());
        }

        // A distinct reason, because it is a distinct fact: the frame was this site's whole
        // checkpoint history and pruning had nothing to do with it.
        verify(metrics, times(MAX_MATERIALIZE_ATTEMPTS)).checkpointBuildAborted("history_gone");
        verify(metrics, never()).checkpointBuildAborted("lossy_refold");
        assertTrue(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));
    }

    @Test
    void skipsTheSiteWhenS3WillNotSayWhetherTheFrameIsThere() {
        // Issue #157. Every alarm below rests on the frame reading as *absent*, and until the
        // tri-state existed a blanket read denial was indistinguishable from absence — so one IAM
        // or bucket-policy change presented as every pruned-history site having lost its history in
        // the same tick. An undecidable answer is not a fact about this site: do nothing with it.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 10L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.UNKNOWN);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(survivor));
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        assertThrows(CheckpointService.FramePresenceUnknownException.class,
                () -> service.buildCheckpoint(SITE));

        // Not an abort of the kind that meter counts: this one repairs itself the moment the
        // permission is back, and delta.checkpoint.builds.aborted promises the opposite.
        verify(metrics, never()).checkpointBuildAborted(any());
        // And nothing durable was decided either way — a refold from zero would be exactly the
        // truncated checkpoint the refusal exists to prevent.
        verify(checkpointStorage, never()).openFrame(any(), anyLong());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        assertEquals(0, owing.materializeAttempts());
    }

    @Test
    void doesNotRetireTheRowsOfASiteWhoseFrameWasMerelyUnreadable() {
        // The half #149 made durable: with no segments behind it, an unreadable frame is classified
        // history_gone and spends an attempt on every retryable row, and after
        // max-materialize-attempts such nights the site names itself on no work list at all — so it
        // does not come back on its own once the permission returns. A denial must never reach that
        // classification.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 2L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.UNKNOWN);
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        for (int tick = 1; tick <= MAX_MATERIALIZE_ATTEMPTS + 1; tick++) {
            assertThrows(CheckpointService.FramePresenceUnknownException.class,
                    () -> service.buildCheckpoint(SITE));
        }

        verify(metrics, never()).checkpointBuildAborted(any());
        assertEquals(0, owing.materializeAttempts(),
                "a permissions incident must not spend the retry that would outlive it");
        assertFalse(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));
    }

    @Test
    void stillRefusesALossyRefoldWhileSegmentsSurvive() {
        // The other side of that split, and it must keep shouting: segments on record mean a
        // refold would publish a truncated checkpoint over real data, and the site is visited for
        // those segments every night whatever any attempt counter says.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 10L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(12L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(survivor));
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(metrics).checkpointBuildAborted("lossy_refold");
        verify(metrics, never()).checkpointBuildAborted("history_gone");
        assertEquals(0, owing.materializeAttempts(), "a site that is visited anyway cannot drain");
    }

    @Test
    void refusesARefoldOverAGapBehindARetainedHead() {
        // Issue #212 review, A1. The prune used to delete oldest-first unconditionally, so
        // "head at seq 1" proved contiguity; the hold-back can now retain an older *pending*
        // segment while its younger processed neighbours are pruned, leaving a gap behind a head
        // that still starts at 1 (a reinit re-pends interleaved segments out of queue order —
        // markFullSnapshotPluginSqlProcessed / clearPluginSqlBySiteId — which is the concrete
        // route). The old proxy read that as "full history survives" and silently published a
        // truncated checkpoint from seq 0, advancing the pointer over the loss.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 6L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(6L, 6L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 6L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment retainedHead = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/head", "DELTA", Map.of());
        ChangelogSegment afterTheGap = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 5L, 6L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(retainedHead, afterTheGap)); // seqs 3..4 are gone
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(metrics).checkpointBuildAborted("lossy_refold");
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void spendsAnAttemptWhenOnlyBelowCheckpointSegmentsRemainBehindAGoneFrame() {
        // Issue #212 review, A3. Everything the changelog holds is already inside the lost frame's
        // fold, so no new work will ever change the verdict — and a held-back pending segment can
        // keep the list non-empty for ever, where before #212 retention emptied it and the site
        // reached history_gone's bounded drain. The same bound applies here now.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 10L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment heldBack = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 5L, 6L, 2L, "hash", "s3/held", "DELTA", Map.of());
        stubSiteSegments(List.of(heldBack));
        // R2-7: the drain applies only while a held-back *pending* segment pins the state open.
        when(segmentRepository.existsCommittedPendingBelowCheckpoint(SITE, 10L)).thenReturn(true);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        for (int attempt = 1; attempt <= MAX_MATERIALIZE_ATTEMPTS; attempt++) {
            S3CheckpointStorage.CheckpointStorageException thrown =
                    assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                            () -> service.buildCheckpoint(SITE));
            assertEquals(attempt, owing.materializeAttempts(),
                    "every such night spends one attempt, the #149 drain");
            // R2-6: #186 shows this text to the operator, so it must describe what happened.
            assertTrue(thrown.getMessage().contains("an attempt was spent"),
                    "the scheduled pass says it spent an attempt: " + thrown.getMessage());
        }

        // The tag stays lossy_refold — the segments are real data and the condition is the
        // pruned-history one — and the alarm fired once per night while the retry lasted.
        verify(metrics, times(MAX_MATERIALIZE_ATTEMPTS)).checkpointBuildAborted("lossy_refold");
        verify(metrics, never()).checkpointBuildAborted("history_gone");
        assertTrue(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));

        // Drained: the visit ends quietly — no further increment, nothing thrown, nothing written.
        // The site stays pinned to the work list by its held-back segments;
        // delta.checkpoint.tables.given-up is the standing signal from here on.
        service.buildCheckpoint(SITE);

        verify(metrics, times(MAX_MATERIALIZE_ATTEMPTS)).checkpointBuildAborted("lossy_refold");
        assertEquals(MAX_MATERIALIZE_ATTEMPTS, owing.materializeAttempts(),
                "a drained site spends nothing further");
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
    }

    @Test
    void aForcedRebuildRearmsTheRowsBehindAGoneFrameWithOnlyBelowCheckpointSegments() {
        // The FORCE half of the drain above, #149's own rule: a forced rebuild is the operator
        // asserting the cause was dealt with, so it re-arms the retry instead of spending it —
        // and it still fails loudly, because the frame is still gone.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 10L, 1L);
        for (int i = 0; i < MAX_MATERIALIZE_ATTEMPTS; i++) {
            owing.recordFailedMaterialization();
        }
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment heldBack = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 5L, 6L, 2L, "hash", "s3/held", "DELTA", Map.of());
        stubSiteSegments(List.of(heldBack));
        when(segmentRepository.existsCommittedPendingBelowCheckpoint(SITE, 10L)).thenReturn(true);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        S3CheckpointStorage.CheckpointStorageException thrown =
                assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                        () -> service.rebuildFromFrame(SITE));

        verify(metrics).checkpointBuildAborted("lossy_refold");
        assertEquals(0, owing.materializeAttempts(), "the forced pass re-arms instead of spending");
        assertFalse(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS));
        // R2-6: #186 puts this text into the admin lastRebuildMessage verbatim — telling the
        // operator their re-arm action spent an attempt would be false.
        assertTrue(thrown.getMessage().contains("re-armed"),
                "the forced pass says it re-armed, not spent: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("an attempt was spent"),
                "the forced pass must not claim an attempt was spent: " + thrown.getMessage());
    }

    @Test
    void keepsTheNeverQuietsAlarmForProcessedOnlyBelowCheckpointSegments() {
        // R2-7 of the #212 review. A frame-gone quiet site holding its ordinary processed audit
        // window is the pre-#212 population — retention never emptied that list (default window
        // 20), and the alarm is a real, rebuild-recoverable data-loss condition. The drain is
        // scoped to the state #212 created (a held-back *pending* segment below the pointer);
        // without one, this must stay loud every night and spend nothing, for ever.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 10L, 1L);
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(10L, 10L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment processed = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 5L, 6L, 2L, "hash", "s3/processed", "DELTA", Map.of());
        processed.markEgressed();
        processed.markPluginSqlProcessed();
        stubSiteSegments(List.of(processed));
        when(segmentRepository.existsCommittedPendingBelowCheckpoint(SITE, 10L)).thenReturn(false);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        for (int tick = 1; tick <= MAX_MATERIALIZE_ATTEMPTS + 2; tick++) {
            assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                    () -> service.buildCheckpoint(SITE));
        }

        verify(metrics, times(MAX_MATERIALIZE_ATTEMPTS + 2)).checkpointBuildAborted("lossy_refold");
        assertEquals(0, owing.materializeAttempts(),
                "the pre-#212 population never drains: no attempt is ever spent");
    }

    @Test
    void refusesTheFoldWhenTheChangelogChangedBetweenTheCoverageReadAndTheEntityLoad() {
        // R2-5 of the #212 review. The coverage read and the entity load are two transactions
        // with an S3 frame probe between them; a deleter that bumps no epoch (batch retention's
        // 45-day horizon, a sibling replica's prune) can remove rows in the window. The coverage
        // said "contiguous from seq 1" so the full-refold gate passed — but the list actually
        // folded is gapped, and folding it would publish truncated checkpoints and advance the
        // pointer over the loss. The stubs are deliberately inconsistent: that *is* the race.
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(4L, 4L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 4L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment head = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 1L, 2L, 2L, "hash", "s3/head", "DELTA", Map.of());
        ChangelogSegment tail = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 3L, 4L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        when(segmentRepository.findSeqRangesBySiteIdOrderByFirstSeq(SITE))
                .thenReturn(List.of(rangeOf(head), rangeOf(tail)));
        when(segmentRepository.findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(SITE, 0L))
                .thenReturn(List.of(tail)); // head deleted between the reads

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        // Transient race, not a permanent abort: the next tick re-reads and classifies the state,
        // so the never-repairs-itself meter must not move (the read-denial rule).
        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void aForcedRebuildOnAFramelessSiteRearmsItsRowsInsteadOfSpendingThem() {
        // Review of PR #169. A forced rebuild is the operator asserting the cause has been dealt
        // with, and it is the documented recovery from exactly this state — so it must not be the
        // fastest way to exhaust the retry it is supposed to restore.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 2L, 1L);
        owing.recordFailedMaterialization();
        owing.recordFailedMaterialization();
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(2L, 2L, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.ABSENT);
        stubSiteSegments(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(owing));

        assertThrows(S3CheckpointStorage.CheckpointStorageException.class,
                () -> service.rebuildFromFrame(SITE));

        assertEquals(0, owing.materializeAttempts(),
                "the documented recovery must re-arm the retry, not consume it");
        verify(checkpointRepository).save(owing);
    }

    @Test
    void neverReapsEveryCheckpointRowOfASite() {
        // Review of PR #169, round 1. If every table were emptied at the source the fold would be
        // empty and the reap would delete the site's whole `checkpoints` set — and "this site has
        // no checkpoint rows" is load-bearing elsewhere: CheckpointFileQueryService reads it as
        // "not a Delta site yet" and answers a Bit BI client with the pre-Delta uploaded CSVs, as
        // its own comment says it must never do.
        Checkpoint customers = Checkpoint.create(SITE, "customers", 2L, 0L);
        Checkpoint orders = Checkpoint.create(SITE, "orders", 2L, 0L);
        parkAtPointer(2L, frameOf(), customers, orders);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.buildCheckpoint(SITE);

        verify(checkpointRepository, never()).deleteById(any());
    }

    @Test
    void settlesASiteWhoseWholeFoldIsEmptyInsteadOfVisitingItForever() {
        // Review of PR #169, round 2 — the hole the fix above opened. Sparing the rows is right,
        // but the per-table settle lives inside the snapshot loop and an empty fold never enters
        // it, so the site would be named, folded and abandoned every night without ever spending
        // an attempt: the unbounded retry this ticket removes, minus even the visibility.
        Checkpoint owing = Checkpoint.create(SITE, "customers", 2L, 0L);
        parkAtPointer(2L, frameOf(), owing);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        for (int attempt = 1; attempt <= MAX_MATERIALIZE_ATTEMPTS; attempt++) {
            service.buildCheckpoint(SITE);
            assertEquals(attempt, owing.materializeAttempts());
        }

        assertTrue(owing.hasGivenUpMaterializing(MAX_MATERIALIZE_ATTEMPTS),
                "an empty fold must drain the same way every other unfixable state does");
        verify(checkpointRepository, never()).deleteById(any());
    }

    @Test
    void aForcedRebuildRearmsASiteWhoseWholeFoldIsEmpty() {
        Checkpoint givenUp = Checkpoint.create(SITE, "customers", 2L, 0L);
        for (int i = 0; i < MAX_MATERIALIZE_ATTEMPTS; i++) {
            givenUp.recordFailedMaterialization();
        }
        parkAtPointer(2L, frameOf(), givenUp);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of());

        service.rebuildFromFrame(SITE);

        assertEquals(0, givenUp.materializeAttempts(),
                "the documented recovery re-arms on this path too");
    }

    @Test
    void reapsNothingWhileTheProcessIsShuttingDown() {
        // The reap is a durable write like any other, so the #162 rule covers it: a build that is
        // only ending must not delete rows against a DataSource that is being closed.
        Checkpoint vanished = Checkpoint.create(SITE, "orders", 2L, 0L);
        Checkpoint customers = Checkpoint.create(SITE, "customers", 2L, 1L);
        parkAtPointer(2L, frameOf(record("customers", 1L, 1, "Ann")), customers, vanished);
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        shuttingDown = true;

        assertEquals(Map.of(), service.buildCheckpoint(SITE));

        verify(checkpointRepository, never()).deleteById(any());
    }

    @Test
    void keepsACheckpointRowWhoseTableIsStillInTheFoldWithNoSurvivingRows() {
        // The boundary of the reap. A table whose rows were all deleted *in this build's own
        // segments* is still a key in the fold, with an empty row map — it gets an empty snapshot,
        // exactly as before. Only the next build, seeded from a frame that no longer mentions it,
        // sees it as gone. Reaping it a build early would delete a row the build is still writing.
        stubSegmentRecords("s3/segment", List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob"),
                deletion("orders", 3L, 2)));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        Checkpoint emptied = Checkpoint.create(SITE, "orders", 1L, 1L);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(emptied));
        when(checkpointRepository.findBySiteIdAndTableName(SITE, "orders")).thenReturn(Optional.of(emptied));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        verify(checkpointRepository, never()).deleteById(any());
        verify(checkpointStorage).uploadParquet(eq(SITE), eq("orders"), anyLong(), any(Path.class));
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
        stubSiteSegments(List.of());
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
        when(checkpointStorage.framePresence(SITE, 2L)).thenReturn(ObjectPresence.ABSENT);
        stubSiteSegments(List.of());

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

        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE), "a discarded build publishes nothing");

        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
        // Not even the frame, and not by luck: since issue #153 put the frame ahead of the
        // snapshots, `requireEpoch` runs ahead of the frame. A wipe that has already committed is
        // therefore seen before the longest unguarded stretch of the build instead of after it.
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        verify(checkpointStorage, never()).uploadParquet(any(), any(), anyLong(), any());
    }

    @Test
    void checksTheEpochBeforeUploadingTheFrame() {
        // The positive half of the above. The frame is neither a row nor a pointer, so nothing
        // else in the build refuses on its behalf; without this check the build's first contact
        // with the site_sync_state lock would come only after a multi-GiB write and PUT.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        InOrder order = inOrder(syncStateRepository, checkpointStorage);
        order.verify(syncStateRepository).findBySiteIdForUpdate(SITE);
        order.verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
    }

    @Test
    void leavesAnOrphanFrameWhenTheWipeCommitsAfterThePreCheck() {
        // The residual window the pre-check cannot close, kept honest rather than claimed away: a
        // wipe committing after `requireEpoch` passed leaves `_frame/seq=2/frame.pb.gz` behind and
        // the guard speaks at the first row write instead. That object is the same litter a
        // discarded build has always left (the snapshots it had already uploaded), and the wipe's
        // own cut-off spares anything newer than its start (#122), so a second wipe collects it.
        //
        // It is harmless for a reason that is *not* "the new epoch never reaches seq 2": a wipe
        // resets the client's counters, so the site re-traverses the same seq range and a later
        // build may well end at 2 — at which point it overwrites this object with its own. The
        // real invariant is that a build only ever seeds from the frame at last_checkpoint_seq,
        // and uploadFrame(N) always precedes the recordCheckpoint(N) that names it, so the frame
        // read is always the one written by the build that moved the pointer there.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        wipedAfterThePreCheck(1L);

        // Since #186 the discard is thrown rather than returned as an empty fold, so a forced
        // rebuild can tell it from a build that published something; nothing else about the ending
        // moves — no row, no pointer, no event.
        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE));

        verify(checkpointStorage).uploadFrame(eq(SITE), eq(2L), any(Path.class));
        verify(checkpointRepository, never()).save(any());
        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void doesNotMistakeAWipeForAPerTableParquetFailure() {
        // The per-table catch exists so one unrenderable table cannot freeze the pointer. An epoch
        // change is the opposite: nothing about this build may be published, so it must escape the
        // catch instead of being counted as a skip and letting the next table try.
        stubSegmentRecords("s3/segment", List.of(
                record("customers", 1L, 1, "Ann"),
                record("orders", 2L, 2, "Bob")));
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "customers", customersSchema(),
                "orders", customersSchema()));
        recordUploads("checkpoints/parquet-key");
        // The wipe must land *after* the pre-frame epoch check, or the build never reaches the
        // table loop this test is about.
        wipedAfterThePreCheck(1L);

        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE));

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

        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE));

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
        when(checkpointStorage.framePresence(SITE, 10L)).thenReturn(ObjectPresence.ABSENT);
        ChangelogSegment survivor = ChangelogSegment.create(
                SITE, UUID.randomUUID(), 11L, 12L, 2L, "hash", "s3/tail", "DELTA", Map.of());
        stubSiteSegments(List.of(survivor));

        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE));

        verify(syncStateService, never()).recordCheckpoint(any(), anyLong());
        verify(checkpointStorage, never()).uploadFrame(any(), anyLong(), any(Path.class));
        // And it must not reach the abort counter either: a build discarded because the operator
        // replaced the site's history is a normal outcome, not a frozen pointer to page on.
        verify(metrics, never()).checkpointBuildAborted(any());
    }

    @Test
    void takesTheEpochLockBeforeWritingTheRowAndThePointer() {
        // The positive half: every write of a healthy build goes through the same row lock the
        // wipe holds, which is what makes the two orderings the only possible ones.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of("customers", customersSchema()));
        recordUploads("checkpoints/parquet-key");

        service.buildCheckpoint(SITE);

        // Three: the pre-frame check (#153), the table's row write, and the pointer.
        verify(syncStateRepository, times(3)).findBySiteIdForUpdate(SITE);
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

        assertThrows(CheckpointService.BuildDiscardedException.class,
                () -> service.buildCheckpoint(SITE), "a discarded build publishes nothing");

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
            stubSiteSegments(List.of());
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
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(wiped(generation)));
    }

    /**
     * The wipe commits in the residual window: the build's pre-frame {@code requireEpoch} still
     * sees the old epoch, everything after it sees the new one. This is what the per-table catch
     * and the frame orphan are about, so it has to be modelled rather than collapsed into
     * {@link #wipedTo(long)} — which now refuses the build before it writes anything at all.
     */
    private void wipedAfterThePreCheck(long generation) {
        when(syncStateRepository.findBySiteIdForUpdate(SITE))
                .thenReturn(Optional.of(SiteSyncState.initial(SITE)))
                .thenReturn(Optional.of(wiped(generation)));
    }

    private static SiteSyncState wiped(long generation) {
        SiteSyncState state = SiteSyncState.initial(SITE);
        for (long i = 0; i < generation; i++) {
            state.resetForWipe();
        }
        return state;
    }

    /**
     * Park the site at an already-recorded checkpoint: the pointer matches the last segment,
     * the frame is readable, and the given rows are what {@code findBySiteId} returns. A build
     * in this state used to be a no-op because {@code newSegments} is empty.
     */
    private void parkAtPointer(long seq, byte[] frameBytes, Checkpoint... rows) {
        when(syncStateService.getSyncState(SITE)).thenReturn(new SyncStateView(seq, seq, 1, false, false, 0L, 0L));
        when(checkpointStorage.framePresence(SITE, seq)).thenReturn(ObjectPresence.PRESENT);
        stubFrame(seq, frameBytes);
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

    /** The gzipped all-INSERT frame a previous build would have left at the pointer. */
    private static byte[] frameOf(ChangeRecord... records) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            ChangelogCodec.write(List.of(records), out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A DELETE of one row by key — the op that empties a table and, one build later, removes it. */
    private static ChangeRecord deletion(String table, long seq, long id) {
        Value idVal = Value.newBuilder().setIntValue(id).build();
        return ChangeRecord.newBuilder()
                .setTable(table)
                .setOp(Op.DELETE)
                .setSeq(seq)
                .putKey("id", idVal)
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
