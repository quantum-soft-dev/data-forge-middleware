package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * #14 — the checkpoint scheduler builds + prunes each site with changelog data, and a failure for one
 * site must not abort the others.
 */
class CheckpointSchedulerTest {

    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final ChangelogRetentionService retentionService = mock(ChangelogRetentionService.class);
    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final CheckpointScheduler scheduler =
            new CheckpointScheduler(checkpointService, retentionService, segmentRepository);

    @Test
    void buildsAndPrunesEachSite() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(a, b));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(a);
        verify(retentionService).prune(a);
        verify(checkpointService).buildCheckpoint(b);
        verify(retentionService).prune(b);
    }

    @Test
    void oneSiteFailureDoesNotAbortOthers() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(failing, ok));
        when(checkpointService.buildCheckpoint(failing)).thenThrow(new RuntimeException("boom"));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(ok);
        verify(retentionService).prune(ok);
        verify(retentionService, never()).prune(failing); // build threw before prune
    }
}
