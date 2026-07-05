package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.infrastructure.BatchWithFileCountProjection;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.infrastructure.JpaSiteRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    void listBatchHistoryIncludesDeltaTotalsFromBulkFetchedSegments() {
        UUID accountId = UUID.randomUUID();
        Site site = Site.createForTesting(accountId, "delta.test", "Delta Test");
        when(siteRepository.findByAccountId(accountId)).thenReturn(List.of(site));

        UUID batchId = UUID.randomUUID();
        BatchWithFileCountProjection projection = mock(BatchWithFileCountProjection.class);
        when(projection.getId()).thenReturn(batchId);
        when(projection.getSiteId()).thenReturn(site.getId());
        when(projection.getStatus()).thenReturn("COMPLETED");
        when(projection.getHasErrors()).thenReturn(false);
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now());
        when(projection.getCompletedAt()).thenReturn(LocalDateTime.now());
        when(projection.getFileCount()).thenReturn(0);
        when(projection.getTotalSize()).thenReturn(0L);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));

        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getBatchId()).thenReturn(batchId);
        when(segment.getRecordCount()).thenReturn(34L);
        when(segment.getStats()).thenReturn(Map.of(
                "a", new TableChangeStats(1, 0, 0), "b", new TableChangeStats(0, 1, 0)));
        when(changelogSegmentRepository.findByBatchIdIn(anyList())).thenReturn(List.of(segment));

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertEquals(34L, dto.deltaRecordCount());
        assertEquals(2, dto.deltaTableCount());
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
        when(projection.getStartedAt()).thenReturn(LocalDateTime.now());
        when(projection.getCompletedAt()).thenReturn(LocalDateTime.now());
        when(projection.getFileCount()).thenReturn(2);
        when(projection.getTotalSize()).thenReturn(2048L);
        when(batchRepository.findBySiteIdsFirstPage(anyList(), anyInt())).thenReturn(List.of(projection));
        when(changelogSegmentRepository.findByBatchIdIn(anyList())).thenReturn(List.of());

        CursorPageResponseDto<BatchSummaryDto> page = service.listBatchHistory(accountId, null, 20);

        BatchSummaryDto dto = page.items().get(0);
        assertNull(dto.deltaRecordCount());
        assertNull(dto.deltaTableCount());
    }
}
