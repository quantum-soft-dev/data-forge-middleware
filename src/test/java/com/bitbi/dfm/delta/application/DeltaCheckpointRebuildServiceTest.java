package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * B7 (023) — forced out-of-schedule checkpoint rebuild: sets the persistent rebuild flag,
 * runs {@code CheckpointService.buildCheckpoint} asynchronously, and always clears the flag
 * when the attempt finishes (success or failure).
 */
class DeltaCheckpointRebuildServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);

    /** Direct executor: the async hop runs inline so the full lifecycle is observable. */
    private final DeltaCheckpointRebuildService service =
            new DeltaCheckpointRebuildService(syncStateService, checkpointService, Runnable::run);

    @Test
    void requestRebuildSetsFlagBuildsCheckpointAndClearsFlag() {
        when(checkpointService.buildCheckpoint(SITE)).thenReturn(Map.of());

        service.requestRebuild(SITE);

        InOrder inOrder = inOrder(syncStateService, checkpointService);
        inOrder.verify(syncStateService).requestRebuild(SITE);
        inOrder.verify(checkpointService).buildCheckpoint(SITE);
        inOrder.verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void clearsFlagEvenWhenBuildFails() {
        when(checkpointService.buildCheckpoint(SITE)).thenThrow(new RuntimeException("s3 down"));

        assertDoesNotThrow(() -> service.requestRebuild(SITE));

        verify(syncStateService).clearRebuildRequested(SITE);
    }
}
