package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchDeltaSegment;
import com.bitbi.dfm.batch.domain.BatchTableStats;
import com.bitbi.dfm.batch.infrastructure.BatchWithFileCountProjection;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.SegmentBatchAggregate;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.infrastructure.JpaSiteRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * T6.4/T6.5 — batch history surfaces a Delta v2 batch's real per-run signal (per-table
 * insert/update/delete counts in detail, totals in the list view) instead of the always-zero
 * file count (Delta writes no {@code uploaded_files}).
 */
class BatchHistoryServiceTest {

    private final JpaBatchRepository batchRepository = mock(JpaBatchRepository.class);
    private final JpaSiteRepository siteRepository = mock(JpaSiteRepository.class);
    private final ChangelogSegmentRepository changelogSegmentRepository = mock(ChangelogSegmentRepository.class);
    private final BatchHistoryService service =
            new BatchHistoryService(batchRepository, siteRepository, changelogSegmentRepository);

    @Test
    void getBatchDetailsIncludesDeltaStatsWhenSegmentExists() {
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, siteId);
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getStats()).thenReturn(Map.of(
                "orders", new TableChangeStats(2, 1, 0),
                "customers", new TableChangeStats(0, 0, 3)));
        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId())).thenReturn(List.of(segment));

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertEquals(2, dto.deltaStats().size());
        assertEquals("customers", dto.deltaStats().get(0).table());
        assertEquals(3, dto.deltaStats().get(0).deletes());
        assertEquals("orders", dto.deltaStats().get(1).table());
        assertEquals(2, dto.deltaStats().get(1).inserts());
    }

    @Test
    void getBatchDetailsHasEmptyDeltaStatsForV1Batch() {
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, siteId);
        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId())).thenReturn(List.of());

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertTrue(dto.deltaStats().isEmpty());
        assertNull(dto.mode(), "v1 batch has no session mode");
        assertNull(dto.seqRange(), "v1 batch has no seq range");
    }

    @Test
    void getBatchDetailsExposesModeAndSeqRangeAcrossSegments() {
        // B9: mode + seqRange {first,last} from the batch's changelog segments
        // (min firstSeq / max lastSeq over all segments of the session).
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, siteId);

        ChangelogSegment first = mock(ChangelogSegment.class);
        when(first.getStats()).thenReturn(Map.of("orders", new TableChangeStats(2, 1, 0)));
        when(first.getMode()).thenReturn("CONTINUOUS");
        when(first.getFirstSeq()).thenReturn(4801L);
        when(first.getLastSeq()).thenReturn(4950L);

        ChangelogSegment second = mock(ChangelogSegment.class);
        when(second.getStats()).thenReturn(Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(second.getMode()).thenReturn("CONTINUOUS");
        when(second.getFirstSeq()).thenReturn(4951L);
        when(second.getLastSeq()).thenReturn(5100L);

        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId())).thenReturn(List.of(second, first));

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertEquals("CONTINUOUS", dto.mode());
        assertEquals(4801L, dto.seqRange().first());
        assertEquals(5100L, dto.seqRange().last());
    }

    @Test
    void getBatchDetailsAggregatesDuplicateTableStatsAcrossSegmentsAndIgnoresLegacyNullStats() {
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, siteId);

        ChangelogSegment first = mock(ChangelogSegment.class);
        when(first.getStats()).thenReturn(Map.of(
                "orders", new TableChangeStats(10, 2, 1),
                "customers", new TableChangeStats(3, 0, 0)));
        when(first.getMode()).thenReturn("FULL_SNAPSHOT");
        when(first.getFirstSeq()).thenReturn(1L);
        when(first.getLastSeq()).thenReturn(16L);

        ChangelogSegment legacy = mock(ChangelogSegment.class);
        when(legacy.getStats()).thenReturn(null);
        when(legacy.getMode()).thenReturn("FULL_SNAPSHOT");
        when(legacy.getFirstSeq()).thenReturn(17L);
        when(legacy.getLastSeq()).thenReturn(20L);

        ChangelogSegment last = mock(ChangelogSegment.class);
        when(last.getStats()).thenReturn(Map.of(
                "orders", new TableChangeStats(5, 4, 2),
                "customers", new TableChangeStats(0, 1, 1)));
        when(last.getMode()).thenReturn("FULL_SNAPSHOT");
        when(last.getFirstSeq()).thenReturn(21L);
        when(last.getLastSeq()).thenReturn(34L);

        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId()))
                .thenReturn(List.of(last, legacy, first));

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertEquals(2, dto.deltaStats().size());
        assertEquals("customers", dto.deltaStats().get(0).table());
        assertEquals(3, dto.deltaStats().get(0).inserts());
        assertEquals(1, dto.deltaStats().get(0).updates());
        assertEquals(1, dto.deltaStats().get(0).deletes());
        assertEquals("orders", dto.deltaStats().get(1).table());
        assertEquals(15, dto.deltaStats().get(1).inserts());
        assertEquals(6, dto.deltaStats().get(1).updates());
        assertEquals(3, dto.deltaStats().get(1).deletes());
    }

    @Test
    void listBatchHistoryIncludesDeltaTotalsAggregatedAcrossSegments() {
        // 029: a session batch owns N segments; the list row shows the batch-level aggregate
        // (SUM of record counts, DISTINCT table count) computed SQL-side per page.
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.test", "Delta Test");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));

        UUID batchId = UUID.randomUUID();
        BatchWithFileCountProjection projection = mock(BatchWithFileCountProjection.class);
        when(projection.getId()).thenReturn(batchId);
        when(projection.getSiteId()).thenReturn(site.getId());
        when(projection.getStatus()).thenReturn("COMPLETED");
        when(projection.getHasErrors()).thenReturn(false);
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getCompletedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getFileCount()).thenReturn(0);
        when(projection.getTotalSize()).thenReturn(0L);
        // Started before V58: no stored totals, so the segments are read (issue #346).
        when(projection.getTotalRecords()).thenReturn(null);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));

        SegmentBatchAggregate aggregate = mock(SegmentBatchAggregate.class);
        when(aggregate.getBatchId()).thenReturn(batchId);
        when(aggregate.getTotalRecords()).thenReturn(250L);
        when(aggregate.getTableCount()).thenReturn(2L);
        when(changelogSegmentRepository.aggregateByBatchIds(anyList())).thenReturn(List.of(aggregate));

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertEquals(250L, dto.deltaRecordCount());
        assertEquals(2, dto.deltaTableCount());
    }

    @Test
    void listBatchHistoryMapsZeroTableCountToNull() {
        // Segments persisted before per-table stats existed aggregate to a 0 distinct-table count;
        // the DTO keeps the pre-029 rendering (no table badge) by mapping 0 to null.
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.old", "Delta Old");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));

        UUID batchId = UUID.randomUUID();
        BatchWithFileCountProjection projection = mock(BatchWithFileCountProjection.class);
        when(projection.getId()).thenReturn(batchId);
        when(projection.getSiteId()).thenReturn(site.getId());
        when(projection.getStatus()).thenReturn("COMPLETED");
        when(projection.getHasErrors()).thenReturn(false);
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getCompletedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getFileCount()).thenReturn(0);
        when(projection.getTotalSize()).thenReturn(0L);
        // Started before V58: no stored totals, so the segments are read (issue #346).
        when(projection.getTotalRecords()).thenReturn(null);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));

        SegmentBatchAggregate aggregate = mock(SegmentBatchAggregate.class);
        when(aggregate.getBatchId()).thenReturn(batchId);
        when(aggregate.getTotalRecords()).thenReturn(34L);
        when(aggregate.getTableCount()).thenReturn(0L);
        when(changelogSegmentRepository.aggregateByBatchIds(anyList())).thenReturn(List.of(aggregate));

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertEquals(34L, dto.deltaRecordCount());
        assertNull(dto.deltaTableCount());
    }

    @Test
    void listBatchHistoryHasNullDeltaTotalsForV1Batches() {
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "v1.test", "V1 Test");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));

        UUID batchId = UUID.randomUUID();
        BatchWithFileCountProjection projection = mock(BatchWithFileCountProjection.class);
        when(projection.getId()).thenReturn(batchId);
        when(projection.getSiteId()).thenReturn(site.getId());
        when(projection.getStatus()).thenReturn("COMPLETED");
        when(projection.getHasErrors()).thenReturn(false);
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getCompletedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getFileCount()).thenReturn(2);
        when(projection.getTotalSize()).thenReturn(2048L);
        when(projection.getTotalRecords()).thenReturn(null);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));
        when(changelogSegmentRepository.aggregateByBatchIds(anyList())).thenReturn(List.of());

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertNull(dto.deltaRecordCount());
        assertNull(dto.deltaTableCount());
    }

    // ---- Issue #346: a finished batch's totals survive changelog retention --------------------

    @Test
    void getBatchDetailsReadsTheStoredTotalsOfAFinishedBatchWhoseSegmentsWerePruned() {
        // Retention deleted every segment of this session (the fyt-new case with an audit window
        // of 0): before #346 the detail degraded to a v1-looking batch with no stats, no mode and
        // no seq range. The totals recorded while the session committed are what is shown.
        UUID accountId = UUID.randomUUID();
        Batch batch = finishedTrackedBatch(accountId, "FULL_SNAPSHOT");
        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId())).thenReturn(List.of());

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertEquals(2, dto.deltaStats().size());
        assertEquals("customers", dto.deltaStats().get(0).table());
        assertEquals(40, dto.deltaStats().get(0).inserts());
        assertEquals("orders", dto.deltaStats().get(1).table());
        assertEquals(60, dto.deltaStats().get(1).inserts());
        assertEquals("FULL_SNAPSHOT", dto.mode());
        assertEquals(1L, dto.seqRange().first());
        assertEquals(100L, dto.seqRange().last());
        verify(changelogSegmentRepository, never()).findByBatchId(any());
    }

    @Test
    void getBatchDetailsOfARunningBatchStillReadsItsSegments() {
        // A running re-baseline's sealed segments are provisional and not in the stored totals
        // until the flip (033), so the live view keeps reading segments while the batch runs.
        UUID accountId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, UUID.randomUUID(), "FULL_SNAPSHOT");
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getStats()).thenReturn(Map.of("orders", new TableChangeStats(100, 0, 0)));
        when(segment.getMode()).thenReturn("FULL_SNAPSHOT");
        when(segment.getFirstSeq()).thenReturn(1L);
        when(segment.getLastSeq()).thenReturn(100L);
        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));
        when(changelogSegmentRepository.findByBatchId(batch.getId())).thenReturn(List.of(segment));

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertEquals(1, dto.deltaStats().size());
        assertEquals(100, dto.deltaStats().get(0).inserts());
        assertEquals(100L, dto.seqRange().last());
    }

    @Test
    void getBatchDetailsOfAFinishedEmptySessionShowsNoSeqRange() {
        UUID accountId = UUID.randomUUID();
        Batch batch = Batch.start(accountId, UUID.randomUUID(), "DELTA");
        batch.complete();
        when(batchRepository.findByIdWithFiles(batch.getId())).thenReturn(Optional.of(batch));

        BatchDetailDto dto = service.getBatchDetails(batch.getId(), accountId);

        assertTrue(dto.deltaStats().isEmpty());
        assertNull(dto.seqRange(), "no segment was ever recorded, so there is no range to show");
    }

    @Test
    void listBatchHistoryReadsTheStoredTotalsOfAFinishedBatchWithoutAskingTheSegments() {
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.pruned", "Delta Pruned");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));
        BatchWithFileCountProjection projection = projection(site, "COMPLETED", 5012611L, 87);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertEquals(5012611L, dto.deltaRecordCount());
        assertEquals(87, dto.deltaTableCount());
        verify(changelogSegmentRepository, never()).aggregateByBatchIds(anyList());
    }

    @Test
    void listBatchHistoryMapsAStoredZeroTableCountToNull() {
        // A finished session that recorded no segment keeps the rendering an empty session always
        // had: no "0 changes • 0 tables" badge.
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.empty", "Delta Empty");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));
        BatchWithFileCountProjection projection = projection(site, "COMPLETED", 0L, 0);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));

        BatchSummaryDto dto = service.listBatchHistory(accountId, null, 20).items().get(0);

        assertNull(dto.deltaRecordCount());
        assertNull(dto.deltaTableCount());
    }

    @Test
    void listBatchHistoryAsksTheSegmentsOnlyForRunningOrUntrackedBatches() {
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.mixed", "Delta Mixed");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));
        BatchWithFileCountProjection finished = projection(site, "COMPLETED", 10L, 1);
        BatchWithFileCountProjection running = projection(site, "IN_PROGRESS", 3L, 1);
        BatchWithFileCountProjection legacy = projection(site, "COMPLETED", null, null);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt()))
                .thenReturn(List.of(finished, running, legacy));
        SegmentBatchAggregate live = aggregate(running.getId(), 7L, 2L);
        SegmentBatchAggregate old = aggregate(legacy.getId(), 4L, 1L);
        when(changelogSegmentRepository.aggregateByBatchIds(anyList())).thenReturn(List.of(live, old));

        List<BatchSummaryDto> rows = service.listBatchHistory(accountId, null, 20).items();

        verify(changelogSegmentRepository).aggregateByBatchIds(List.of(running.getId(), legacy.getId()));
        assertEquals(10L, rows.get(0).deltaRecordCount());
        assertEquals(7L, rows.get(1).deltaRecordCount());
        assertEquals(2, rows.get(1).deltaTableCount());
        assertEquals(4L, rows.get(2).deltaRecordCount());
    }

    private static Batch finishedTrackedBatch(UUID accountId, String mode) {
        Batch batch = Batch.start(accountId, UUID.randomUUID(), mode);
        batch.recordDeltaSegment(new BatchDeltaSegment(60L, 1L, 60L,
                Map.of("orders", new BatchTableStats(60, 0, 0))));
        batch.recordDeltaSegment(new BatchDeltaSegment(40L, 61L, 100L,
                Map.of("customers", new BatchTableStats(40, 0, 0))));
        batch.complete();
        return batch;
    }

    private static BatchWithFileCountProjection projection(Site site, String status,
                                                           Long totalRecords, Integer tableCount) {
        BatchWithFileCountProjection projection = mock(BatchWithFileCountProjection.class);
        UUID id = UUID.randomUUID();
        when(projection.getId()).thenReturn(id);
        when(projection.getSiteId()).thenReturn(site.getId());
        when(projection.getStatus()).thenReturn(status);
        when(projection.getHasErrors()).thenReturn(false);
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(projection.getFileCount()).thenReturn(0);
        when(projection.getTotalSize()).thenReturn(0L);
        when(projection.getTotalRecords()).thenReturn(totalRecords);
        when(projection.getTableCount()).thenReturn(tableCount);
        return projection;
    }

    private static SegmentBatchAggregate aggregate(UUID batchId, long records, long tables) {
        SegmentBatchAggregate aggregate = mock(SegmentBatchAggregate.class);
        when(aggregate.getBatchId()).thenReturn(batchId);
        when(aggregate.getTotalRecords()).thenReturn(records);
        when(aggregate.getTableCount()).thenReturn(tables);
        return aggregate;
    }
}
