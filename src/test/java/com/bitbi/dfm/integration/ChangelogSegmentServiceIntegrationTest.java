package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.5 — persisting a session's accepted records as a changelog segment writes both an S3 object
 * and a metadata row with the correct sequence range / counts / mode.
 */
class ChangelogSegmentServiceIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ChangelogSegmentService service;

    @Autowired
    private ChangelogSegmentRepository repository;

    @Autowired
    private S3ChangelogSegmentStorage storage;

    @Test
    void persistsSegmentRowAndS3Object() {
        List<ChangeRecord> records = List.of(
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.INSERT).setSeq(1L).build(),
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.UPDATE).setSeq(2L).build());

        ChangelogSegment segment = service.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        assertEquals(1L, segment.getFirstSeq());
        assertEquals(2L, segment.getLastSeq());
        assertEquals(2L, segment.getRecordCount());
        assertEquals("FULL_SNAPSHOT", segment.getMode());
        assertNotNull(segment.getContentHash());
        assertTrue(storage.exists(segment.getS3Key()), "segment object must exist in S3");

        ChangelogSegment persisted = repository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        assertEquals(segment.getId(), persisted.getId());
        assertEquals(BATCH, persisted.getBatchId());
        assertEquals(2L, persisted.getRecordCount());
        assertEquals(segment.getContentHash(), persisted.getContentHash());
    }
}
