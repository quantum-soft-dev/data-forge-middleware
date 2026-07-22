package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review r4 — the upload-timeout sweeper must exclude Delta v2 (gRPC) sessions: a CONTINUOUS or
 * resumed delta session can legitimately run past the 60-minute upload timeout, and timing out its
 * batch mid-commit would roll back the segment + watermark. Delta batches are reaped by their own
 * lifecycle, not this sweeper.
 */
@DisplayName("Batch timeout excludes Delta v2 sessions")
class BatchTimeoutExclusionIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("findExpiredBatches returns the V1 batch but not the V2 batch")
    void excludesV2Batches() {
        // Fresh sites (one V1, one V2) so neither collides with an existing active batch.
        UUID v1Site = seedSite("V1", "expiry-v1.example.com");
        UUID v2Site = seedSite("V2", "expiry-v2.example.com");

        LocalDateTime old = LocalDateTime.now().minusHours(3);
        UUID v1Batch = seedInProgressBatch(v1Site, old);
        UUID v2Batch = seedInProgressBatch(v2Site, old);

        List<UUID> expired = batchRepository.findExpiredBatches(LocalDateTime.now())
                .stream().map(Batch::getId).toList();

        assertTrue(expired.contains(v1Batch), "V1 upload batch is swept by the timeout");
        assertFalse(expired.contains(v2Batch), "V2 delta batch must be excluded from the timeout");
    }

    private UUID seedSite(String apiVersion, String domain) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, "
                        + "is_active, created_at, updated_at, site_name, client_api_version) "
                        + "VALUES (?, ?, ?, '', ?, true, now(), now(), ?, ?)",
                id, ACCOUNT, domain, apiVersion + " site", domain, apiVersion);
        return id;
    }

    private UUID seedInProgressBatch(UUID siteId, LocalDateTime startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, "
                        + "total_size, has_errors, started_at, created_at, version) "
                        + "VALUES (?, ?, ?, 'IN_PROGRESS', ?, 0, 0, false, ?, ?, 0)",
                id, ACCOUNT, siteId, "s3/" + id, startedAt, startedAt);
        return id;
    }
}
