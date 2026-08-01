package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void deleteMetadataByBatchIdReturnsKeysWithoutDeletingObjects() {
        UUID batchId = UUID.randomUUID();
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getId()).thenReturn(UUID.randomUUID());
        when(segment.getS3Key()).thenReturn("delta/site/segments/1.pb.gz");
        when(repository.findByBatchId(batchId)).thenReturn(List.of(segment));

        assertEquals(List.of("delta/site/segments/1.pb.gz"),
                service.deleteMetadataByBatchId(batchId));

        verify(repository).deleteById(segment.getId());
        verifyNoInteractions(storage);
    }

    @Test
    void persistComputesPerTableStatsFromRecords() {
        UUID site = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        List<ChangeRecord> records = List.of(
                rec("orders", Op.INSERT, 1), rec("orders", Op.INSERT, 2), rec("customers", Op.DELETE, 3));
        when(storage.uploadSegment(eq(site), any(UUID.class), any())).thenReturn("delta/site/segments/x.pb.gz");
        ArgumentCaptor<ChangelogSegment> captor = ArgumentCaptor.forClass(ChangelogSegment.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.persist(site, batch, "DELTA", 1L, records);

        Map<String, TableChangeStats> stats = captor.getValue().getStats();
        assertEquals(new TableChangeStats(2, 0, 0), stats.get("orders"));
        assertEquals(new TableChangeStats(0, 0, 1), stats.get("customers"));
    }

    @Test
    void persistKeysStorageBySegmentIdNotBatchId() {
        UUID site = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        ArgumentCaptor<UUID> keyIdCaptor = ArgumentCaptor.forClass(UUID.class);
        when(storage.uploadSegment(eq(site), keyIdCaptor.capture(), any()))
                .thenAnswer(inv -> "delta/" + site + "/segments/" + inv.getArgument(1) + ".pb.gz");
        ArgumentCaptor<ChangelogSegment> captor = ArgumentCaptor.forClass(ChangelogSegment.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ChangelogSegment saved = service.persist(site, batch, "CONTINUOUS", 1L,
                List.of(rec("orders", Op.INSERT, 1)));

        // The storage key is derived from the segment's own id (and thus matches the row),
        // never from the batch id — N segments of one batch must not collide.
        assertEquals(saved.getId(), keyIdCaptor.getValue());
        assertNotEquals(batch, keyIdCaptor.getValue());
        assertEquals("delta/" + site + "/segments/" + saved.getId() + ".pb.gz", saved.getS3Key());
    }

    @Test
    void persistTwiceForSameBatchUsesDistinctStorageKeys() {
        UUID site = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        ArgumentCaptor<UUID> keyIdCaptor = ArgumentCaptor.forClass(UUID.class);
        when(storage.uploadSegment(eq(site), keyIdCaptor.capture(), any()))
                .thenAnswer(inv -> "delta/" + site + "/segments/" + inv.getArgument(1) + ".pb.gz");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.persist(site, batch, "CONTINUOUS", 1L, List.of(rec("orders", Op.INSERT, 1)));
        service.persist(site, batch, "CONTINUOUS", 2L, List.of(rec("orders", Op.INSERT, 2)));

        List<UUID> keyIds = keyIdCaptor.getAllValues();
        assertEquals(2, keyIds.size());
        assertNotEquals(keyIds.get(0), keyIds.get(1));
    }

    private static ChangeRecord rec(String table, Op op, long seq) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq).build();
    }
}
