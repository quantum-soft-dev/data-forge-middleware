package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.site.infrastructure.JpaSiteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * T6.4 — batch history detail surfaces a Delta v2 batch's per-table insert/update/delete
 * counts (from its changelog segment) instead of always showing an empty file list.
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
    }
}
