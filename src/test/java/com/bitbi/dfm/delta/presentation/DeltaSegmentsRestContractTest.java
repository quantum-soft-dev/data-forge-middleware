package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B6 (023) — contract tests for the recent-segments REST endpoint (admin only):
 * GET /api/v1/sites/{siteId}/delta/segments?limit=20, sorted createdAt desc.
 *
 * <p>Admin-only per open product decision P2 (owner lite projection deferred) —
 * see specs/022-delta-client-v2/ui-redesign-tasks.md.</p>
 */
@DisplayName("Delta Segments REST Contract Tests")
class DeltaSegmentsRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** COMPLETED batch of store-01 from test-data.sql (changelog_segments.batch_id FK). */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/segments";
    private static final String USER_URL = "/api/v1/account/sites/%s/delta/segments";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSegments() {
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE);
    }

    private void seedSegment(long firstSeq, long lastSeq, long records, String mode, String createdAt) {
        jdbc.update("""
                INSERT INTO changelog_segments
                    (id, site_id, batch_id, first_seq, last_seq, record_count, content_hash, s3_key, mode, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'hash', 'changelog/x', ?, ?::timestamp)
                """, UUID.randomUUID(), SITE, BATCH, firstSeq, lastSeq, records, mode, createdAt);
    }

    @Test
    @DisplayName("returns segments sorted by createdAt desc with all admin fields")
    void shouldListSegmentsNewestFirst() throws Exception {
        seedSegment(1, 400, 400, "FULL_SNAPSHOT", "2026-07-01 10:00:00");
        seedSegment(401, 950, 550, "DELTA", "2026-07-03 10:00:00");
        seedSegment(951, 1200, 250, "CONTINUOUS", "2026-07-05 10:00:00");

        mockMvc.perform(get(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].firstSeq").value(951))
                .andExpect(jsonPath("$[0].lastSeq").value(1200))
                .andExpect(jsonPath("$[0].recordCount").value(250))
                .andExpect(jsonPath("$[0].mode").value("CONTINUOUS"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].mode").value("DELTA"))
                .andExpect(jsonPath("$[2].mode").value("FULL_SNAPSHOT"));
    }

    @Test
    @DisplayName("respects the limit parameter (default 20)")
    void shouldRespectLimit() throws Exception {
        seedSegment(1, 100, 100, "DELTA", "2026-07-01 10:00:00");
        seedSegment(101, 200, 100, "DELTA", "2026-07-02 10:00:00");
        seedSegment(201, 300, 100, "DELTA", "2026-07-03 10:00:00");

        mockMvc.perform(get(ADMIN_URL.formatted(SITE) + "?limit=2")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].firstSeq").value(201))
                .andExpect(jsonPath("$[1].firstSeq").value(101));
    }

    @Test
    @DisplayName("returns empty array when the site has no segments")
    void shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("returns 404 for an unknown site")
    void shouldReturn404ForUnknownSite() throws Exception {
        mockMvc.perform(get(ADMIN_URL.formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 403 for a non-admin user on the admin route")
    void shouldReturn403ForUserRole() throws Exception {
        mockMvc.perform(get(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no owner-facing segments route exists (P2 pending)")
    void ownerRouteAbsent() throws Exception {
        mockMvc.perform(get(USER_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status < 400) {
                        throw new AssertionError(
                                "Owner segments route must not exist until P2 is resolved, but answered " + status);
                    }
                });
    }
}
