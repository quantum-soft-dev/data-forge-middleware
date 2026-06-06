package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * #9 — the commit service runs persist + advance-watermark + complete-batch as one ordered unit
 * (atomic via @Transactional), and writes no segment for an empty session.
 */
class DeltaSessionCommitServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();

    private final ChangelogSegmentService segmentService = mock(ChangelogSegmentService.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchLifecycleService batchLifecycleService = mock(BatchLifecycleService.class);
    private final DeltaSessionCommitService commit =
            new DeltaSessionCommitService(segmentService, syncStateService, batchLifecycleService);

    @Test
    void persistsThenAdvancesThenCompletes() {
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/b.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(1L).build());

        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 1L, records);

        assertEquals("delta/site/segments/b.pb.gz", key);
        InOrder order = inOrder(segmentService, syncStateService, batchLifecycleService);
        order.verify(segmentService).persist(SITE, BATCH, "DELTA", 1L, records);
        order.verify(syncStateService).advanceWatermark(SITE, 1L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void emptySessionWritesNoSegmentButStillCompletesBatch() {
        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 0L, List.of());

        assertEquals("", key);
        verify(segmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(syncStateService).advanceWatermark(SITE, 0L);
        verify(batchLifecycleService).completeBatch(BATCH);
    }
}
