package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B5 (023) — contract tests for the Delta checkpoints REST endpoints:
 * GET /api/v1/account/sites/{siteId}/delta/checkpoints (owner; admin variant under /api/v1/sites)
 * and the per-click presigned download
 * GET .../delta/checkpoints/{tableName}/download?format=parquet ({@code csv} is retired — #113).
 */
@DisplayName("Delta Checkpoints REST Contract Tests")
class DeltaCheckpointsRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com — owned by test account 1 (the mock tokens' account). */
    private static final UUID OWNED_SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-03.example.com — owned by a different account. */
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final String USER_URL = "/api/v1/account/sites/%s/delta/checkpoints";
    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/checkpoints";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanCheckpoints() {
        jdbc.update("DELETE FROM checkpoints WHERE site_id IN (?, ?)", OWNED_SITE, FOREIGN_SITE);
    }

    private void seedCheckpoint(UUID siteId, String table, long seq, long rows, String csvKey, String parquetKey) {
        jdbc.update("""
                INSERT INTO checkpoints (id, site_id, table_name, seq, row_count, s3_key_csv, s3_key_parquet, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '2026-07-04 10:00:00', '2026-07-05 09:00:00')
                """, UUID.randomUUID(), siteId, table, seq, rows, csvKey, parquetKey);
    }

    @Nested
    @DisplayName("GET .../delta/checkpoints")
    class ListCheckpoints {

        @Test
        @DisplayName("returns checkpoints sorted by table name with file-presence flags")
        void shouldListCheckpointsSortedWithFlags() throws Exception {
            seedCheckpoint(OWNED_SITE, "orders", 4821, 1_204_500,
                    "checkpoints/x/orders/seq=4821/snapshot.csv.gz",
                    "checkpoints/x/orders/seq=4821/snapshot.parquet");
            seedCheckpoint(OWNED_SITE, "customers", 4821, 88_000,
                    "checkpoints/x/customers/seq=4821/snapshot.csv.gz", null);
            seedCheckpoint(OWNED_SITE, "inventory", 4821, 12, null, null);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].table").value("customers"))
                    .andExpect(jsonPath("$[0].hasCsv").doesNotExist())
                    .andExpect(jsonPath("$[0].hasParquet").value(false))
                    .andExpect(jsonPath("$[1].table").value("inventory"))
                    .andExpect(jsonPath("$[1].hasParquet").value(false))
                    .andExpect(jsonPath("$[2].table").value("orders"))
                    .andExpect(jsonPath("$[2].seq").value(4821))
                    .andExpect(jsonPath("$[2].rowCount").value(1_204_500))
                    .andExpect(jsonPath("$[2].updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$[2].hasParquet").value(true));
        }

        @Test
        @DisplayName("returns empty array when no checkpoints exist")
        void shouldReturnEmptyList() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns 403 for a site owned by another account")
        void shouldReturn403ForForeignSite() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("admin variant returns any site's checkpoints; user role gets 403")
        void adminVariantRoleGating() throws Exception {
            seedCheckpoint(FOREIGN_SITE, "orders", 10, 5, "k.csv.gz", null);

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET .../delta/checkpoints/{tableName}/download")
    class DownloadCheckpoint {

        @Test
        @DisplayName("returns a fresh presigned URL for the requested format")
        void shouldPresignRequestedFormat() throws Exception {
            seedCheckpoint(OWNED_SITE, "orders", 4821, 100,
                    "checkpoints/x/orders/seq=4821/snapshot.csv.gz",
                    "checkpoints/x/orders/seq=4821/snapshot.parquet");

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/orders/download?format=parquet")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                    .andExpect(jsonPath("$.fileName").value(containsString("orders")))
                    .andExpect(jsonPath("$.fileName").value(containsString(".parquet")))
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        }

        @Test
        @DisplayName("returns 410 for the retired csv format, on owner and admin alike")
        void shouldReturn410ForRetiredCsvFormat() throws Exception {
            // The row still carries its historical CSV key — the format is gone regardless (#113).
            seedCheckpoint(OWNED_SITE, "orders", 4821, 100,
                    "checkpoints/x/orders/seq=4821/snapshot.csv.gz",
                    "checkpoints/x/orders/seq=4821/snapshot.parquet");
            seedCheckpoint(FOREIGN_SITE, "orders", 4821, 100,
                    "checkpoints/y/orders/seq=4821/snapshot.csv.gz", null);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/orders/download?format=csv")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isGone());

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE) + "/orders/download?format=csv")
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isGone());
        }

        @Test
        @DisplayName("returns 410 for the retired csv format even when the table is unknown")
        void shouldReturn410ForRetiredCsvFormatOnUnknownTable() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/nope/download?format=csv")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isGone());
        }

        @Test
        @DisplayName("returns 404 when the requested format is not materialized (Parquet pending)")
        void shouldReturn404WhenFormatMissing() throws Exception {
            seedCheckpoint(OWNED_SITE, "customers", 10, 5, "k.csv.gz", null);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/customers/download?format=parquet")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 404 for an unknown table")
        void shouldReturn404ForUnknownTable() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/nope/download?format=parquet")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 400 for an invalid format")
        void shouldReturn400ForInvalidFormat() throws Exception {
            seedCheckpoint(OWNED_SITE, "orders", 10, 5, "k.csv.gz", null);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE) + "/orders/download?format=xml")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 403 for a foreign site's checkpoint download")
        void shouldReturn403ForForeignSite() throws Exception {
            seedCheckpoint(FOREIGN_SITE, "orders", 10, 5, "k.csv.gz", null);

            mockMvc.perform(get(USER_URL.formatted(FOREIGN_SITE) + "/orders/download?format=parquet")
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isForbidden());
        }
    }
}
