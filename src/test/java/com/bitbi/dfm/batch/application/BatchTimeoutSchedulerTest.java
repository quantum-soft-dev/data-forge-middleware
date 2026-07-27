package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 030 (T06) — the timeout sweep must be conditional, and must judge each batch by the same cutoff
 * its SELECT used.
 */
@DisplayName("BatchTimeoutScheduler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchTimeoutSchedulerTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private BatchLifecycleService batchLifecycleService;

    private BatchTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchTimeoutScheduler(batchRepository, batchLifecycleService, 60);
    }

    @Test
    @DisplayName("should hand each batch the very cutoff its SELECT used, not a recomputed one")
    void shouldReuseTheSelectCutoff() {
        // Recomputing "now - timeout" inside the loop would move the goalposts between the SELECT
        // and the transition, so a batch could be judged against a cutoff it was never selected by.
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchRepository.findExpiredBatches(any())).thenReturn(List.of(batch));

        scheduler.checkExpiredBatches();

        ArgumentCaptor<LocalDateTime> selectCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchRepository).findExpiredBatches(selectCutoff.capture());
        ArgumentCaptor<LocalDateTime> transitionCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchLifecycleService)
                .markBatchNotCompletedIfStillExpired(any(), transitionCutoff.capture());

        assertThat(transitionCutoff.getValue()).isEqualTo(selectCutoff.getValue());
    }

    @Test
    @DisplayName("should never use the unconditional transition, which could kill a live session")
    void shouldNeverUseTheUnconditionalTransition() {
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchRepository.findExpiredBatches(any())).thenReturn(List.of(batch));

        scheduler.checkExpiredBatches();

        verify(batchLifecycleService, never()).markBatchNotCompleted(any());
    }

    @Test
    @DisplayName("should tolerate a batch that came back to life between the SELECT and the update")
    void shouldTolerateRevivedBatch() {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchRepository.findExpiredBatches(any())).thenReturn(List.of(batch));
        when(batchLifecycleService.markBatchNotCompletedIfStillExpired(any(), any())).thenReturn(false);

        scheduler.checkExpiredBatches(); // must not throw; the skip is a normal outcome

        verify(batchLifecycleService).markBatchNotCompletedIfStillExpired(any(), any());
    }

    @Test
    @DisplayName("should do nothing when no batch is expired")
    void shouldDoNothingWhenNoneExpired() {
        when(batchRepository.findExpiredBatches(any())).thenReturn(List.of());

        scheduler.checkExpiredBatches();

        verify(batchLifecycleService, never()).markBatchNotCompletedIfStillExpired(any(), any());
    }
}
