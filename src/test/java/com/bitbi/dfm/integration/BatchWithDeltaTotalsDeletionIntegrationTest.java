package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchDeletionService;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.site.application.SiteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A batch carrying stored Delta totals is removed by the two JPA delete paths other than retention
 * (issue #366; retention is {@link BatchRetentionIntegrationTest}).
 * <p>
 * {@code em.remove} deep-copies every mutable attribute into the deleted state, and
 * hypersistence-utils clones a JSON attribute by Java serialization, so a {@code table_stats} map
 * whose values were not {@code Serializable} failed every such delete while loading, changing and
 * flushing the same batch worked. The totals are seeded by SQL in V58's shape — what the V58/V60
 * backfill left on every finished batch — because the entity's own writer is not what is under
 * test. The class commits its rows (the services open their own transactions) and removes whatever
 * a failing test left in {@link #removeSeededRows()}.
 * </p>
 */
@DisplayName("Deleting a batch with stored Delta totals (issue #366)")
class BatchWithDeltaTotalsDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiN4Y7sJpX6dC";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchDeletionService batchDeletionService;

    @Autowired
    private SiteService siteService;

    @Autowired
    private BatchRepository batchRepository;

    private final List<UUID> seededAccounts = new ArrayList<>();

    @AfterEach
    void removeSeededRows() {
        for (UUID accountId : seededAccounts) {
            jdbcTemplate.update("DELETE FROM batches WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM sites WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        seededAccounts.clear();
    }

    @Test
    @DisplayName("The admin batch delete removes a batch with stored totals")
    void batchDeletionRemovesABatchWithStoredTotals() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        UUID batchId = seedBatchWithTotals(accountId, siteId);

        assertThat(batchDeletionService.deleteBatch(batchId)).isTrue();

        assertThat(batchRepository.existsById(batchId)).isFalse();
    }

    @Test
    @DisplayName("Deleting a site removes its batches with stored totals")
    void siteDeletionRemovesABatchWithStoredTotals() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        UUID batchId = seedBatchWithTotals(accountId, siteId);

        siteService.deleteSite(siteId);

        assertThat(batchRepository.existsById(batchId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sites WHERE id = ?", Long.class, siteId)).isZero();
    }

    private UUID seedAccount() {
        UUID accountId = UUID.randomUUID();
        seededAccounts.add(accountId);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, name, is_active, created_at, updated_at) VALUES (?,?,?,?,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC')",
                accountId, "totals-" + accountId + "@example.com", "Totals Test", true);
        return accountId;
    }

    private UUID seedSite(UUID accountId) {
        UUID siteId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, retention_days, created_at, updated_at, site_name) VALUES (?,?,?,?,?,?,?,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC',?)",
                siteId, accountId, siteId + ".example.com", SECRET_HASH, "Totals Site", true, 45,
                "example.com");
        return siteId;
    }

    private UUID seedBatchWithTotals(UUID accountId, UUID siteId) {
        UUID batchId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count,
                    total_size, has_errors, started_at, completed_at, created_at, version,
                    session_mode, total_records, table_count, first_seq, last_seq, table_stats)
                VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false,
                    NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC', 0,
                    'FULL_SNAPSHOT', 3, 2, 1, 3,
                    '{"orders":{"inserts":2,"updates":0,"deletes":0},"items":{"inserts":1,"updates":0,"deletes":0}}'::jsonb)
                """, batchId, siteId, accountId, "path/" + batchId + "/");
        return batchId;
    }
}
