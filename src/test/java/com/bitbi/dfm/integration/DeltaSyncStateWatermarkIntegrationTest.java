package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T2.8 — advancing the per-site watermark creates the sync-state row and advances it monotonically.
 */
class DeltaSyncStateWatermarkIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

    @Autowired
    private DeltaSyncStateService service;

    @Autowired
    private SiteSyncStateRepository repository;

    @Test
    void advanceWatermarkCreatesAndAdvancesMonotonically() {
        service.advanceWatermark(SITE, 10L);
        assertEquals(10L, repository.findBySiteId(SITE).orElseThrow().getLastAppliedSeq());

        service.advanceWatermark(SITE, 25L);
        assertEquals(25L, repository.findBySiteId(SITE).orElseThrow().getLastAppliedSeq());

        service.advanceWatermark(SITE, 20L); // lower seq is a no-op (monotonic)
        assertEquals(25L, repository.findBySiteId(SITE).orElseThrow().getLastAppliedSeq());
    }
}
