package com.bitbi.dfm.delta.application;

import ch.qos.logback.classic.Level;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import com.bitbi.dfm.util.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.bitbi.dfm.delta.application.EgressTestSupport.captureUploads;
import static com.bitbi.dfm.delta.application.EgressTestSupport.egress;
import static com.bitbi.dfm.delta.application.EgressTestSupport.failSegment;
import static com.bitbi.dfm.delta.application.EgressTestSupport.stubSegment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A poison table (data that cannot be rendered against its declared schema) must not wedge the
 * egress queue: the render failure is scoped to that table, the remaining tables still publish,
 * and the segment is marked egressed — the skip-and-continue contract CheckpointService already
 * documents. Upload failures stay fatal (transient S3 errors must retry the segment).
 *
 * <p>Issue #404: the segment is streamed and each table written to a scratch file, never held in
 * heap; and the queue spends an attempt and takes a lease <em>when it claims</em>, so a process
 * killed mid-work (an {@link OutOfMemoryError}, a liveness kill) leaves the segment in backoff
 * instead of handing it straight to the next process.</p>
 */
@ExtendWith(MockitoExtension.class)
class DeltaEgressServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final String KEY = "changelog/key";

    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private ChangelogSegmentService changelogSegmentService;
    @Mock
    private SiteSchemaService siteSchemaService;
    @Mock
    private S3CheckpointStorage storage;

    @TempDir
    Path scratch;

    private SimpleMeterRegistry registry;
    private ApplicationShutdownSignal shutdownSignal;
    private DeltaEgressService service;

    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        shutdownSignal = new ApplicationShutdownSignal();
        service = egress(segmentRepository, changelogSegmentService, siteSchemaService, storage,
                registry, shutdownSignal, scratch);
        segment = ChangelogSegment.create(SITE_ID, UUID.randomUUID(), 1L, 2L, 2L,
                "hash", KEY, "DELTA", null);
    }

    @Test
    void shouldSkipPoisonTableAndStillEgressRemainingTables() throws IOException {
        // "bad" renders first (stream order) and fails coercion: boolean column with garbage text.
        stubSegment(changelogSegmentService, KEY, List.of(
                insert("bad", 1L, Map.of("id", intVal(1), "flag", strVal("not-a-boolean"))),
                insert("good", 2L, Map.of("id", intVal(2)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "bad", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false),
                        new ColumnDefinition("flag", "boolean", false)), List.of("id"), List.of()),
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        Map<String, byte[]> uploads = captureUploads(storage);

        service.egressSegment(segment);

        assertEquals(List.of("good"), List.copyOf(uploads.keySet()));
        verify(storage, never()).uploadDelta(eq(SITE_ID), eq("bad"), anyLong(), anyLong(), any(Path.class));
        verify(segmentRepository).markEgressed(segment.getId());
        verify(segmentRepository, never()).save(segment);
        assertEquals(1.0, registry.get("delta.egress.segments").counter().count());
        assertEquals(1L, phaseCount("download"));
        assertEquals(1L, phaseCount("write"), "every table is written in one shared pass");
        assertEquals(1L, phaseCount("upload"));
        assertEquals(1L, phaseCount("total"));
        assertScratchEmpty();
    }

    /**
     * Issue #382, review round 1: the per-segment delta file moves
     * {@code delta.parquet.unrepresentable-decimals{reason=overflow}} for a cell past 38 digits.
     */
    @Test
    void shouldCountADecimalCellPastThirtyEightDigitsAsOverflow() {
        stubSegment(changelogSegmentService, KEY, List.of(
                insert("stock", 1L, Map.of("id", intVal(1), "qty", decVal(".12E+40"))),
                insert("stock", 2L, Map.of("id", intVal(2), "qty", decVal("1.50")))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "stock", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false),
                        new ColumnDefinition("qty", "numeric(7,2)", true)), List.of("id"), List.of())));
        Map<String, byte[]> uploads = captureUploads(storage);

        service.egressSegment(segment);

        assertTrue(uploads.containsKey("stock"));
        verify(storage).uploadDelta(eq(SITE_ID), eq("stock"), eq(1L), eq(2L), any(Path.class));
        assertEquals(1.0, registry.get("delta.parquet.unrepresentable-decimals")
                .tag("reason", "overflow").counter().count());
        assertEquals(0.0, registry.get("delta.parquet.unrepresentable-decimals")
                .tag("reason", "non_finite").counter().count());
    }

    @Test
    void shouldRecordEgressPhasesOnASuccessfulSegment() {
        stubSegment(changelogSegmentService, KEY, List.of(insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        captureUploads(storage);

        service.egressSegment(segment);

        assertEquals(1L, phaseCount("download"));
        assertEquals(1L, phaseCount("write"));
        assertEquals(1L, phaseCount("upload"));
        assertEquals(1L, phaseCount("total"));
        assertEquals(1.0, registry.get("delta.egress.segments").counter().count());
    }

    @Test
    void shouldKeepSegmentPendingWhenUploadFails() throws IOException {
        stubSegment(changelogSegmentService, KEY, List.of(insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        when(storage.uploadDelta(eq(SITE_ID), eq("good"), anyLong(), anyLong(), any(Path.class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        assertThrows(RuntimeException.class, () -> service.egressSegment(segment),
                "transient upload failure must roll the segment back for the sweep");
        assertNull(segment.getEgressAt());
        verify(segmentRepository, never()).markEgressed(any());
        assertScratchEmpty();
    }

    /**
     * Issue #404: a segment whose stats name its tables is read exactly once — the tables are known
     * before the stream starts, so every one of them is written from the same pass. The pass is
     * what keeps the heap independent of the segment's size.
     */
    @Test
    void shouldReadASegmentWithStatsInOnePass() {
        ChangelogSegment withStats = ChangelogSegment.create(SITE_ID, UUID.randomUUID(), 1L, 2L, 2L,
                "hash", KEY, "DELTA", Map.of("a", new TableChangeStats(1, 0, 0),
                        "b", new TableChangeStats(1, 0, 0)));
        stubSegment(changelogSegmentService, KEY, List.of(
                insert("a", 1L, Map.of("id", intVal(1))), insert("b", 2L, Map.of("id", intVal(2)))));
        TableSchema idOnly = new TableSchema(List.of(new ColumnDefinition("id", "bigint", false)),
                List.of("id"), List.of());
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of("a", idOnly, "b", idOnly));
        Map<String, byte[]> uploads = captureUploads(storage);

        service.egressSegment(withStats);

        verify(changelogSegmentService, times(1)).forEachRecord(eq(KEY), any(), any());
        assertEquals(2, uploads.size());
    }

    /**
     * Review round 2 of #404: one writer per table at once would trade the segment-size bound for a
     * table-count one — 87 tables x 16 MiB row groups x two workers is more heap than the pod has.
     * The tables are written {@code delta.egress.table-writers} at a time, one pass over the segment
     * per group, and each group's files are uploaded and deleted before the next opens.
     */
    @Test
    void shouldWriteTheTablesAtMostTableWritersAtATime() {
        DeltaEgressService oneAtATime = new DeltaEgressService(segmentRepository, changelogSegmentService,
                siteSchemaService, storage, new DeltaMetrics(registry),
                new DeltaParquetProperties(EgressTestSupport.ROW_GROUP_BYTES),
                new ParquetScratchBudget(registry, 0L), scratch.toString(), Long.MAX_VALUE, 1, 60, 7, 600,
                shutdownSignal);
        ChangelogSegment withStats = ChangelogSegment.create(SITE_ID, UUID.randomUUID(), 1L, 2L, 2L,
                "hash", KEY, "DELTA", Map.of("a", new TableChangeStats(1, 0, 0),
                        "b", new TableChangeStats(1, 0, 0)));
        stubSegment(changelogSegmentService, KEY, List.of(
                insert("a", 1L, Map.of("id", intVal(1))), insert("b", 2L, Map.of("id", intVal(2)))));
        TableSchema idOnly = new TableSchema(List.of(new ColumnDefinition("id", "bigint", false)),
                List.of("id"), List.of());
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of("a", idOnly, "b", idOnly));
        List<Long> filesOnDiskAtUpload = new java.util.ArrayList<>();
        when(storage.uploadDelta(eq(SITE_ID), anyString(), anyLong(), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> {
                    try (Stream<Path> files = Files.list(scratch)) {
                        filesOnDiskAtUpload.add(files.count());
                    }
                    return "egress/key";
                });

        oneAtATime.egressSegment(withStats);

        verify(changelogSegmentService, times(2)).forEachRecord(eq(KEY), any(), any());
        assertEquals(List.of(1L, 1L), filesOnDiskAtUpload,
                "one table's file at a time: the first group is deleted before the second opens");
        verify(segmentRepository).markEgressed(withStats.getId());
    }

    @Test
    void shouldRefuseFewerThanOneTableWriterNamingTheKey() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new DeltaEgressService(segmentRepository, changelogSegmentService, siteSchemaService,
                        storage, new DeltaMetrics(registry),
                        new DeltaParquetProperties(EgressTestSupport.ROW_GROUP_BYTES),
                        new ParquetScratchBudget(registry, 0L), scratch.toString(), Long.MAX_VALUE, 0,
                        60, 7, 600, shutdownSignal));
        assertTrue(refused.getMessage().contains("delta.egress.table-writers"), refused.getMessage());
    }

    /**
     * A segment written before stats existed names no tables, so one extra streamed pass finds them
     * — still a stream, never a list.
     */
    @Test
    void shouldFindTheTablesOfASegmentWithoutStatsByStreamingIt() {
        stubSegment(changelogSegmentService, KEY, List.of(insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        Map<String, byte[]> uploads = captureUploads(storage);

        service.egressSegment(segment);

        verify(changelogSegmentService, times(2)).forEachRecord(eq(KEY), any(), any());
        assertTrue(uploads.containsKey("good"));
    }

    /**
     * A full scratch directory is the pod's condition, not the table's: skipping the table would
     * mark the segment egressed without its file, for ever. The whole segment fails instead and the
     * queue defers it like any transient failure.
     */
    @Test
    void shouldFailTheSegmentRatherThanSkipATableWhenScratchIsFull() throws IOException {
        DeltaEgressService tight = new DeltaEgressService(segmentRepository, changelogSegmentService,
                siteSchemaService, storage, new DeltaMetrics(registry),
                new DeltaParquetProperties(EgressTestSupport.ROW_GROUP_BYTES),
                new ParquetScratchBudget(registry, 1L), scratch.toString(), Long.MAX_VALUE, 8, 60, 7, 600,
                shutdownSignal);
        stubSegment(changelogSegmentService, KEY, List.of(insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));

        assertThrows(RuntimeException.class, () -> tight.egressSegment(segment));

        verify(storage, never()).uploadDelta(any(), anyString(), anyLong(), anyLong(), any(Path.class));
        verify(segmentRepository, never()).markEgressed(any());
        assertEquals(1.0, registry.get("delta.parquet.scratch.refused")
                .tag("writer", "egress_segment").counter().count());
        assertScratchEmpty();
    }

    /**
     * Issue #404: the claim spends the attempt and takes a lease before any work starts, so the
     * second worker of this pod and every other replica stop being offered the segment the moment
     * one of them has it.
     */
    @Test
    void shouldSpendTheAttemptAndTakeALeaseBeforeTheWork() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        segmentIsOffered();
        stubSegment(changelogSegmentService, KEY, List.of(insert("good", 1L, Map.of("id", intVal(1)))));
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of(
                "good", new TableSchema(List.of(
                        new ColumnDefinition("id", "bigint", false)), List.of("id"), List.of())));
        captureUploads(storage);

        assertTrue(service.egressNextPending());

        ArgumentCaptor<LocalDateTime> leaseUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        InOrder order = inOrder(segmentRepository, changelogSegmentService);
        order.verify(segmentRepository).leaseEgress(eq(segment.getId()), leaseUntil.capture(), eq(0));
        order.verify(changelogSegmentService, atLeastOnce()).forEachRecord(eq(KEY), any(), any());
        order.verify(segmentRepository).markEgressed(segment.getId());
        assertTrue(leaseUntil.getValue().isAfter(before.plusSeconds(599)),
                "the lease covers the work, not just the first retry delay: " + leaseUntil.getValue());
    }

    /**
     * Review round 1 of #404: a segment that kills its process leaves no failure line, so the claim
     * that takes it again past the threshold is the one place that can name it. Its attempts may
     * also have been spent by thrown failures — the line says both.
     */
    @Test
    void shouldNameASegmentClaimedAgainPastThePoisonThreshold() {
        ReflectionTestUtils.setField(segment, "egressAttempts", 7);
        segmentIsOffered();
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of());
        stubSegment(changelogSegmentService, KEY, List.of());

        try (LogCapture capture = LogCapture.attachTo(DeltaEgressService.class)) {
            assertTrue(service.egressNextPending());

            List<String> warnings = capture.messagesContaining(Level.WARN, segment.getId().toString());
            assertEquals(1, warnings.size(), "one line naming the segment: " + capture.messagesAt(Level.WARN));
            assertTrue(warnings.get(0).contains("8"), "with the attempt it is now on: " + warnings.get(0));
        }
    }

    @Test
    void shouldNotWarnWhenClaimingASegmentBelowThePoisonThreshold() {
        ReflectionTestUtils.setField(segment, "egressAttempts", 5);
        segmentIsOffered();
        when(siteSchemaService.getTableSchemas(SITE_ID)).thenReturn(Map.of());
        stubSegment(changelogSegmentService, KEY, List.of());

        try (LogCapture capture = LogCapture.attachTo(DeltaEgressService.class)) {
            assertTrue(service.egressNextPending());

            assertEquals(List.of(), capture.messagesContaining(Level.WARN, segment.getId().toString()));
        }
    }

    /** Another worker leased the segment between the read and the lease: this one does nothing. */
    @Test
    void shouldLeaveASegmentAnotherWorkerLeasedFirst() {
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of(segment));
        when(segmentRepository.leaseEgress(any(), any(), anyInt())).thenReturn(0);

        assertTrue(service.egressNextPending(), "the drain goes on to the next head");

        verify(changelogSegmentService, never()).forEachRecord(any(), any(), any());
        verify(segmentRepository, never()).markEgressed(any());
        verify(segmentRepository, never()).deferEgress(any(), any(), anyInt());
    }

    /**
     * The incident of 01.11.2026: an {@link Error} is not caught, and nothing has to be — the
     * attempt was spent at the claim and the lease keeps the segment out of the queue, so the next
     * process takes another site's head instead of dying on the same one.
     */
    @Test
    void shouldLeaveTheLeaseInPlaceWhenTheWorkDiesWithAnError() {
        segmentIsOffered();
        org.mockito.Mockito.doThrow(new OutOfMemoryError("Java heap space"))
                .when(changelogSegmentService).forEachRecord(eq(KEY), any(), any());

        assertThrows(OutOfMemoryError.class, () -> service.egressNextPending());

        verify(segmentRepository).leaseEgress(eq(segment.getId()), any(), eq(0));
        verify(segmentRepository, never()).deferEgress(any(), any(), anyInt());
        verify(segmentRepository, never()).releaseEgress(any(), anyInt());
        verify(segmentRepository, never()).markEgressed(any());
    }

    /**
     * Issue #243: the queue path swallows the failure into a durable deferral, so the drain moves
     * on to another site instead of ending on this segment for ever. The unit of work
     * ({@link DeltaEgressService#egressSegment}) still throws — that is what the deferral reads.
     * Since #404 the attempt was already counted by the claim, so the deferral only moves the
     * retry time, scoped to the count the claim wrote.
     */
    @Test
    void shouldDeferAFailingSegmentAndEndThisDrain() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        failingSegmentIsClaimed();

        assertFalse(service.egressNextPending(),
                "this drain stops after one deferral; the next wake claims another site's head, "
                        + "which is what keeps a systemic failure from walking the whole backlog");

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(segmentRepository).deferEgress(eq(segment.getId()), retryAt.capture(), eq(1));
        assertTrue(retryAt.getValue().isAfter(before.plusSeconds(59)),
                "the first failure waits the base delay: " + retryAt.getValue());
        assertNull(segment.getEgressAt(), "the segment stays the durable queue entry");
        verify(segmentRepository, never()).save(any());
        verify(segmentRepository, never()).markEgressed(any());
        assertEquals(1.0, registry.get("delta.egress.errors").counter().count());
        assertEquals(0.0, registry.get("delta.egress.segments.poisoned").counter().count());
    }

    /** A segment that keeps failing stops being an ordinary retry and says so (issue #243). */
    @Test
    void shouldReportASegmentPoisonedOnceItPassesTheAttemptThreshold() {
        ReflectionTestUtils.setField(segment, "egressAttempts", 6);
        failingSegmentIsClaimed();

        assertFalse(service.egressNextPending());

        verify(segmentRepository).deferEgress(eq(segment.getId()), any(), eq(7));
        assertEquals(1.0, registry.get("delta.egress.segments.poisoned").counter().count());
        assertEquals(1.0, registry.get("delta.egress.errors").counter().count());
    }

    /**
     * #162's rule, review round 1: a failure because the pod is closing is the process ending, not
     * the segment failing — the S3 client may already be gone — so it records no verdict. Since
     * #404 the claim already spent an attempt, so the claim is handed back.
     */
    @Test
    void shouldGiveTheClaimBackWhenThePodIsShuttingDown() {
        segmentIsOffered();
        failSegment(changelogSegmentService, KEY, new RuntimeException("S3 client is closed"));
        shutdownSignal.onApplicationEvent(null);

        assertFalse(service.egressNextPending());

        verify(segmentRepository).releaseEgress(segment.getId(), 1);
        verify(segmentRepository, never()).deferEgress(any(), any(), anyInt());
        assertEquals(0.0, registry.get("delta.egress.errors").counter().count());
    }

    /**
     * The work landed on another replica while this attempt was failing — the lease ran out, a
     * reset moved the count — so the deferral matches no row, and reporting it would send an
     * operator after a segment that is already done (review round 1).
     */
    @Test
    void shouldNotReportADeferralThatMatchedNoRow() {
        failingSegmentIsClaimed();
        when(segmentRepository.deferEgress(any(), any(), anyInt())).thenReturn(0);

        assertFalse(service.egressNextPending());

        assertEquals(0.0, registry.get("delta.egress.errors").counter().count());
        assertEquals(0.0, registry.get("delta.egress.segments.poisoned").counter().count());
    }

    private void segmentIsOffered() {
        when(segmentRepository.findNextPendingEgress(eq(1), any())).thenReturn(List.of(segment));
        when(segmentRepository.leaseEgress(any(), any(), anyInt())).thenReturn(1);
    }

    private void failingSegmentIsClaimed() {
        segmentIsOffered();
        when(segmentRepository.deferEgress(any(), any(), anyInt())).thenReturn(1);
        failSegment(changelogSegmentService, KEY, new RuntimeException("object unreadable"));
    }

    /** Both new series exist from startup, so an alert can predate the first failure. */
    @Test
    void shouldRegisterTheFailureSeriesAtZero() {
        assertEquals(0.0, registry.get("delta.egress.errors").counter().count());
        assertEquals(0.0, registry.get("delta.egress.segments.poisoned").counter().count());
    }

    private void assertScratchEmpty() throws IOException {
        try (Stream<Path> files = Files.list(scratch)) {
            assertEquals(List.of(), files.toList(), "every scratch file is deleted with the segment");
        }
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

    private static Value decVal(String v) {
        return Value.newBuilder().setDecimalValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
