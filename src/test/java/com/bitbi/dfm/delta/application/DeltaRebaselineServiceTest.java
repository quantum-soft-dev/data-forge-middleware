package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * #3 — a FULL_SNAPSHOT re-baseline wipes the prior changelog segments and checkpoints and resets the
 * watermark so the snapshot becomes the new baseline; the schema version is preserved.
 */
class DeltaRebaselineServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final S3ChangelogSegmentStorage segmentStorage = mock(S3ChangelogSegmentStorage.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final DeltaRebaselineService service = new DeltaRebaselineService(
            segmentRepository, segmentStorage, checkpointRepository, syncStateRepository);

    @Test
    void resetClearsSegmentsCheckpointsAndResetsWatermarkKeepingSchema() {
        UUID segId = UUID.randomUUID();
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getId()).thenReturn(segId);
        when(segment.getS3Key()).thenReturn("delta/site/segments/old.pb.gz");
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(segment));

        UUID cpId = UUID.randomUUID();
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getId()).thenReturn(cpId);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(checkpoint));

        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        state.recordCheckpoint(100L);
        state.recordSchemaVersion(3);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.reset(SITE, 200L);

        verify(segmentStorage).delete("delta/site/segments/old.pb.gz");
        verify(segmentRepository).deleteById(segId);
        verify(checkpointRepository).deleteById(cpId);

        ArgumentCaptor<SiteSyncState> saved = ArgumentCaptor.forClass(SiteSyncState.class);
        verify(syncStateRepository).save(saved.capture());
        assertEquals(199L, saved.getValue().getLastAppliedSeq(), "watermark reset to firstSeq-1");
        assertEquals(0L, saved.getValue().getLastCheckpointSeq(), "checkpoint pointer cleared");
        assertEquals(3, saved.getValue().getSchemaVersion(), "schema version preserved");
    }
}
