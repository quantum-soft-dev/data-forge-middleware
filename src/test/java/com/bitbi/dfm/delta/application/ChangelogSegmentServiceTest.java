package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * #7 — a batch's changelog segments (S3 + metadata) are removed before the batch so the
 * changelog_segments.batch_id foreign key does not block retention/admin deletion.
 */
class ChangelogSegmentServiceTest {

    private final S3ChangelogSegmentStorage storage = mock(S3ChangelogSegmentStorage.class);
    private final ChangelogSegmentRepository repository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService service = new ChangelogSegmentService(storage, repository);

    @Test
    void deleteByBatchIdRemovesS3ObjectAndRowForEachSegment() {
        UUID batchId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChangelogSegment s1 = mock(ChangelogSegment.class);
        when(s1.getId()).thenReturn(id1);
        when(s1.getS3Key()).thenReturn("delta/site/segments/1.pb.gz");
        ChangelogSegment s2 = mock(ChangelogSegment.class);
        when(s2.getId()).thenReturn(id2);
        when(s2.getS3Key()).thenReturn("delta/site/segments/2.pb.gz");
        when(repository.findByBatchId(batchId)).thenReturn(List.of(s1, s2));

        service.deleteByBatchId(batchId);

        verify(storage).delete("delta/site/segments/1.pb.gz");
        verify(repository).deleteById(id1);
        verify(storage).delete("delta/site/segments/2.pb.gz");
        verify(repository).deleteById(id2);
    }

    @Test
    void deleteByBatchIdWithNoSegmentsIsNoop() {
        UUID batchId = UUID.randomUUID();
        when(repository.findByBatchId(batchId)).thenReturn(List.of());

        service.deleteByBatchId(batchId);

        verify(storage, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }
}
