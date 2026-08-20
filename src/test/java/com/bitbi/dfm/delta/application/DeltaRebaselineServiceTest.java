package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
    private static final UUID BATCH = UUID.randomUUID();

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final S3FileStorageService objectDeleter = mock(S3FileStorageService.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final DeltaRebaselineService service = new DeltaRebaselineService(
            segmentRepository, objectDeleter, checkpointRepository, syncStateRepository);

    {
        when(objectDeleter.deleteObjects(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));
    }

    private static ChangelogSegmentRepository.CommittedSegmentRef ref(UUID id, String key) {
        return new ChangelogSegmentRepository.CommittedSegmentRef() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getS3Key() {
                return key;
            }
        };
    }

    @Test
    void resetClearsSegmentsCheckpointsAndResetsWatermarkKeepingSchema() {
        UUID oldSegId = UUID.randomUUID();
        when(segmentRepository.findCommittedRefsBySiteId(SITE))
                .thenReturn(List.of(ref(oldSegId, "delta/site/segments/old.pb.gz")));
        when(segmentRepository.deleteByIdIn(List.of(oldSegId))).thenReturn(1);

        UUID cpId = UUID.randomUUID();
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getId()).thenReturn(cpId);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(checkpoint));

        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        state.recordCheckpoint(100L);
        state.recordSchemaVersion(3);
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(state));

        service.reset(SITE, 200L);

        // One batched DeleteObjects, not one round trip per key (R2-8) — and one bulk row DELETE
        // over exactly the ids whose keys were collected, so rows and objects keep their identity.
        verify(objectDeleter).deleteObjects(List.of("delta/site/segments/old.pb.gz"));
        verify(segmentRepository).deleteByIdIn(List.of(oldSegId));
        verify(segmentRepository, never()).deleteById(any());
        verify(checkpointRepository).deleteById(cpId);

        ArgumentCaptor<SiteSyncState> saved = ArgumentCaptor.forClass(SiteSyncState.class);
        verify(syncStateRepository).save(saved.capture());
        assertEquals(199L, saved.getValue().getLastAppliedSeq(), "watermark reset to firstSeq-1");
        assertEquals(0L, saved.getValue().getLastCheckpointSeq(), "checkpoint pointer cleared");
        assertEquals(3, saved.getValue().getSchemaVersion(), "schema version preserved");
        assertEquals(1L, saved.getValue().getBaselineEpoch(),
                "the baseline epoch is what refuses a checkpoint build that overlapped this reset");
        assertEquals(0L, saved.getValue().getGeneration(),
                "the wire epoch stays put: an ordinary re-baseline never tells the client to reset");
    }

    @Test
    void resetTakesTheSyncStateRowLockBeforeItDestroysAnything() {
        // Issue #142. The lock is the same per-site mutex the wipe holds for its whole transaction
        // and the one CheckpointEpochGuard blocks on. Taken first, the two orderings that survive
        // are "the build's write commits before the reset starts" (the reset's own deletes remove
        // it) and "the build waits, then sees the new baseline epoch and is refused". Taken last —
        // as a plain read leaves it — a guarded write can slip between the checkpoint deletes and
        // the epoch bump and outlive the reset.
        InOrder order = inOrder(syncStateRepository, checkpointRepository, segmentRepository);
        when(segmentRepository.findCommittedRefsBySiteId(SITE)).thenReturn(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of());
        when(syncStateRepository.findBySiteIdForUpdate(SITE))
                .thenReturn(Optional.of(SiteSyncState.initial(SITE)));

        service.reset(SITE, 10L);

        order.verify(syncStateRepository).findBySiteIdForUpdate(SITE);
        order.verify(segmentRepository).findCommittedRefsBySiteId(SITE);
        order.verify(checkpointRepository).findBySiteId(SITE);
        verify(syncStateRepository, never()).findBySiteId(SITE);
    }

    @Test
    void resetOnlyDiscardsCommittedSegmentsNotTheSnapshotBeingWritten() {
        // 033: a large re-baseline seals its own segments before SessionEnd calls reset. Those are
        // provisional, and reset must source its delete set from the committed-only query — sweeping
        // them too would destroy the very snapshot that is replacing the baseline.
        when(segmentRepository.findCommittedRefsBySiteId(SITE)).thenReturn(List.of());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of());
        when(syncStateRepository.findBySiteIdForUpdate(SITE)).thenReturn(Optional.of(SiteSyncState.initial(SITE)));

        service.reset(SITE, 10L);

        verify(segmentRepository).findCommittedRefsBySiteId(SITE);
        // An empty committed set issues no DELETE at all — 'WHERE id IN ()' is not a statement.
        verify(segmentRepository, never()).deleteByIdIn(any());
        verify(segmentRepository, never()).deleteBySiteId(any());
        verify(segmentRepository, never()).findProvisionalBySiteId(any());
    }

    @Test
    void deleteProvisionalRemovesOrphanedSnapshotRowsAndObjects() {
        // A re-baseline that never reached SessionEnd leaves invisible segments behind; they hold
        // UNIQUE (site_id, first_seq) slots, so the next attempt collects them before streaming.
        UUID orphanId = UUID.randomUUID();
        ChangelogSegment orphan = mock(ChangelogSegment.class);
        when(orphan.getId()).thenReturn(orphanId);
        when(orphan.getS3Key()).thenReturn("delta/site/segments/orphan.pb.gz");
        when(segmentRepository.findProvisionalByBatchId(BATCH)).thenReturn(List.of(orphan));

        int deleted = service.deleteProvisionalByBatch(BATCH);

        assertEquals(1, deleted);
        verify(segmentRepository).deleteById(orphanId);
        verify(objectDeleter).deleteObjects(List.of("delta/site/segments/orphan.pb.gz"));
        verify(syncStateRepository, never()).save(any());
        verify(checkpointRepository, never()).deleteById(any());
    }

    @Test
    void deleteProvisionalIsANoOpWhenNothingWasLeftBehind() {
        when(segmentRepository.findProvisionalByBatchId(BATCH)).thenReturn(List.of());

        assertEquals(0, service.deleteProvisionalByBatch(BATCH));

        verify(segmentRepository, never()).deleteById(any());
        verifyNoInteractions(objectDeleter);
    }
}
