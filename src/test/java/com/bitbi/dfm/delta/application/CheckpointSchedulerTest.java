package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * #14 — the checkpoint scheduler builds + prunes each site with changelog data, and a failure for one
 * site must not abort the others.
 *
 * <p>Issue #137: the segment table is not the whole work list. A site whose changelog was pruned to
 * nothing still owes a rematerialize when one of its tables has no snapshot, so the tick visits the
 * union of "has segments" and "has an unmaterialized checkpoint".</p>
 */
class CheckpointSchedulerTest {

    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final ChangelogRetentionService retentionService = mock(ChangelogRetentionService.class);
    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private volatile boolean shuttingDown;
    private final ApplicationShutdownSignal shutdownSignal = new ApplicationShutdownSignal() {
        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }
    };
    private static final int MAX_MATERIALIZE_ATTEMPTS = 3;
    private final CheckpointScheduler scheduler = new CheckpointScheduler(
            checkpointService, retentionService, segmentRepository, checkpointRepository,
            new CheckpointRetryProperties(MAX_MATERIALIZE_ATTEMPTS), shutdownSignal);

    @Test
    void buildsAndPrunesEachSite() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(a, b));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of());

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
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of());
        when(checkpointService.buildCheckpoint(failing)).thenThrow(new RuntimeException("boom"));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(ok);
        verify(retentionService).prune(ok);
        verify(retentionService, never()).prune(failing); // build threw before prune
    }

    @Test
    void aSiteWhoseFramePresenceIsUnknownDoesNotStopTheSweep() {
        // Issue #157: a read denial hits every site in the tick, so the one thing that must not
        // happen is the sweep ending on the first of them. It is also not pruned — the pointer did
        // not move, and the build recorded nothing at all.
        UUID denied = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(denied, ok));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS))
                .thenReturn(List.of());
        when(checkpointService.buildCheckpoint(denied))
                .thenThrow(new CheckpointService.FramePresenceUnknownException(denied, 9L));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(ok);
        verify(retentionService).prune(ok);
        verify(retentionService, never()).prune(denied);
    }

    @Test
    void aDeferredSiteDoesNotStopTheSweepAndIsNotPruned() {
        // Issue #178: a forced rebuild held the process's fold budget for longer than the wait
        // allows. Nothing was folded for this site, so the pointer did not move and there is
        // nothing new to prune — but the tick must carry on, the way it does for a read denial.
        UUID deferred = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(deferred, ok));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS))
                .thenReturn(List.of());
        when(checkpointService.buildCheckpoint(deferred))
                .thenThrow(new CheckpointFoldBudget.BuildDeferredException(deferred, 600_000L, false));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(ok);
        verify(retentionService).prune(ok);
        verify(retentionService, never()).prune(deferred);
    }

    @Test
    void visitsASiteWhoseSegmentsArePrunedButHasAnUnmaterializedCheckpoint() {
        // Issue #137: audit-window-segments=0 (or a table detached long enough to age out of the
        // window) leaves the site with no segment row at all, yet buildCheckpoint can still
        // rematerialize that table from the frame.
        UUID pruned = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of());
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of(pruned));

        scheduler.buildCheckpoints();

        verify(checkpointService).buildCheckpoint(pruned);
        verify(retentionService).prune(pruned);
    }

    @Test
    void visitsASiteOnlyOnceWhenItHasBothSegmentsAndAnUnmaterializedCheckpoint() {
        UUID both = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(both));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of(both));

        scheduler.buildCheckpoints();

        verify(checkpointService, times(1)).buildCheckpoint(both);
        verify(retentionService, times(1)).prune(both);
    }

    @Test
    void visitsSegmentSitesBeforeRematerializeOnlySites() {
        // Segment work is the reason the tick exists; a rematerialize is a retry behind it.
        UUID withSegments = UUID.randomUUID();
        UUID rematerializeOnly = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(withSegments));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS))
                .thenReturn(List.of(rematerializeOnly));

        scheduler.buildCheckpoints();

        InOrder order = inOrder(checkpointService);
        order.verify(checkpointService).buildCheckpoint(withSegments);
        order.verify(checkpointService).buildCheckpoint(rematerializeOnly);
    }

    @Test
    void stopsVisitingSitesOnceTheApplicationIsShuttingDown() {
        // Issue #162. The remaining sites would each open a transaction and reach for an S3 client
        // that is about to be destroyed, and every one of those failures is a chance to record a
        // verdict about data from a fact about the process.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of(first, second));
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of());
        when(checkpointService.buildCheckpoint(first)).thenAnswer(invocation -> {
            shuttingDown = true;
            return java.util.Map.of();
        });

        scheduler.buildCheckpoints();

        verify(retentionService).prune(first);
        verify(checkpointService, never()).buildCheckpoint(second);
        verify(retentionService, never()).prune(second);
    }

    @Test
    void visitsNothingWhenNoSiteHasSegmentsOrAnUnmaterializedCheckpoint() {
        // A site whose tables are all materialized and whose segments are gone is not visited:
        // neither query names it, and the tick has no other source of sites.
        when(segmentRepository.findDistinctSiteIds()).thenReturn(List.of());
        when(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(MAX_MATERIALIZE_ATTEMPTS)).thenReturn(List.of());

        scheduler.buildCheckpoints();

        verifyNoInteractions(checkpointService);
        verifyNoInteractions(retentionService);
    }
}
