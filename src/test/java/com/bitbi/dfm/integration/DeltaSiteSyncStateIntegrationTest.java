package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.1 — V29 migration (site_sync_state + sites.client_api_version) and the SiteSyncState
 * JPA round-trip.
 *
 * <p>Note: existing-site backfill to {@code V1} is a property of the {@code ADD COLUMN ...
 * DEFAULT 'V1'} DDL and applies to rows present when the migration runs. The Testcontainers
 * schema is seeded by {@code test-data.sql} <em>after</em> migrations, so those rows take the
 * post-migration default ({@code V2}); the backfill itself is therefore asserted by
 * construction, while the verifiable schema effects are tested below.</p>
 */
class DeltaSiteSyncStateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void clientApiVersionColumnDefaultsToV2AndIsNotNull() {
        String columnDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns " +
                        "WHERE table_name = 'sites' AND column_name = 'client_api_version'",
                String.class);
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns " +
                        "WHERE table_name = 'sites' AND column_name = 'client_api_version'",
                String.class);

        assertTrue(columnDefault != null && columnDefault.contains("V2"),
                "new sites must default to V2, was: " + columnDefault);
        assertEquals("NO", nullable, "client_api_version must be NOT NULL");
    }

    @Test
    void clientApiVersionRejectsValueOutsideV1V2() {
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("UPDATE sites SET client_api_version = 'V3' " +
                        "WHERE id = (SELECT id FROM sites LIMIT 1)"));
    }

    @Test
    void siteSyncStateRoundTrips() {
        UUID siteId = UUID.fromString(
                jdbc.queryForObject("SELECT id::text FROM sites LIMIT 1", String.class));

        SiteSyncState state = SiteSyncState.initial(siteId);
        state.advanceWatermark(42L);
        syncStateRepository.save(state);

        SiteSyncState found = syncStateRepository.findBySiteId(siteId).orElseThrow();
        assertEquals(42L, found.getLastAppliedSeq());
        assertEquals(0L, found.getLastCheckpointSeq());
        assertEquals(0, found.getSchemaVersion());
    }
}
