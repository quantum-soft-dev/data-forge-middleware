package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SeededSiteTeardown} must survive a referencing row that arrives between its child sweep
 * and {@code DELETE FROM batches} (issue #265).
 *
 * <p>The CI log named only {@code DataIntegrityViolationException} at
 * {@code DeltaSessionLivenessIntegrationTest.cleanUpSeededData}'s batch delete. These methods
 * establish which constraint that is, pin the retry, and require a remaining failure to quote
 * {@code SQLSTATE} and the constraint name — the leftover-then-clear shape of #226, against the
 * teardown rather than {@code test-data.sql}.
 *
 * <p>Rows live outside {@code %.example.com} / {@code %@example.com} / {@code *.test.local}, so
 * the fixture cannot reach them; this class removes them itself.
 */
@DisplayName("SeededSiteTeardown")
class SeededSiteTeardownTest extends BaseIntegrationTest {

    private static final String FOREIGN_DOMAIN = "teardown-guard-265.invalid";

    private final UUID account = UUID.randomUUID();
    private final UUID site = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();
    private final UUID segment = UUID.randomUUID();
    private final UUID foreignActivationAccount = UUID.randomUUID();
    private final UUID errorLog = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeGuardRows() {
        jdbc.update("DELETE FROM changelog_segments WHERE id = ?", segment);
        jdbc.update("DELETE FROM account_plugins WHERE account_id IN (?, ?)",
                account, foreignActivationAccount);
        jdbc.update("DELETE FROM error_logs WHERE id = ?", errorLog);
        jdbc.update("DELETE FROM batches WHERE id = ?", batch);
        jdbc.update("DELETE FROM sites WHERE id = ?", site);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", account, foreignActivationAccount);
    }

    @Test
    @DisplayName("DELETE FROM batches is blocked by changelog_segments_batch_id_fkey")
    void deleteFromBatchesIsBlockedByChangelogSegmentsBatchIdFkey() {
        seedAccountSiteBatch();
        insertMarkedSegment();

        DataIntegrityViolationException thrown = catchBatchDelete();

        assertThat(SeededSiteTeardown.describeFkFailure(thrown))
                .as("the constraint the CI log never named: V30, no cascade")
                .contains("SQLSTATE=23503")
                .contains("constraint=changelog_segments_batch_id_fkey");
    }

    @Test
    @DisplayName("DELETE FROM batches is blocked by fk_account_plugins_baseline_batch")
    void deleteFromBatchesIsBlockedByAccountPluginsBaselineBatch() {
        seedAccountSiteBatch();
        insertForeignActivation();

        DataIntegrityViolationException thrown = catchBatchDelete();

        assertThat(SeededSiteTeardown.describeFkFailure(thrown))
                .as("the other non-cascade FK onto batches: V25, ON DELETE RESTRICT")
                .contains("SQLSTATE=23503")
                .contains("constraint=fk_account_plugins_baseline_batch");
    }

    @Test
    @DisplayName("error_logs.batch_id does not block DELETE FROM batches (V22 CASCADE)")
    void errorLogsBatchIdDoesNotBlockDeleteFromBatches() {
        seedAccountSiteBatch();
        insertErrorLog();

        assertThatCode(() -> jdbc.update("DELETE FROM batches WHERE site_id = ?", site))
                .as("V5 had no cascade; V22 added ON DELETE CASCADE, so this cannot be the CI failure")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", batch)).isFalse();
        assertThat(rowExists("SELECT count(*) FROM error_logs WHERE id = ?", errorLog))
                .as("the error log must go with the batch, which is what CASCADE means")
                .isFalse();
    }

    @Test
    @DisplayName("retries and clears a segment that arrives between the sweep and DELETE FROM batches")
    void retriesAndClearsASegmentThatArrivesBetweenSweepAndBatchDelete() {
        seedAccountSiteBatch();

        assertThatCode(() -> SeededSiteTeardown.cleanSite(jdbc, site, this::insertMarkedSegment))
                .as("the retry must re-sweep changelog_segments_batch_id_fkey, not just re-issue DELETE")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", batch)).isFalse();
        assertThat(rowExists("SELECT count(*) FROM changelog_segments WHERE id = ?", segment)).isFalse();
        assertThat(rowExists("SELECT count(*) FROM sites WHERE id = ?", site)).isFalse();
    }

    @Test
    @DisplayName("retries and clears an activation that arrives between the sweep and DELETE FROM batches")
    void retriesAndClearsAnActivationThatArrivesBetweenSweepAndBatchDelete() {
        seedAccountSiteBatch();

        assertThatCode(() -> SeededSiteTeardown.cleanSite(jdbc, site, this::insertForeignActivation))
                .as("the retry must re-sweep fk_account_plugins_baseline_batch as well")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", batch)).isFalse();
        assertThat(rowExists("SELECT count(*) FROM account_plugins WHERE account_id = ?",
                foreignActivationAccount)).isFalse();
        assertThat(rowExists("SELECT count(*) FROM sites WHERE id = ?", site)).isFalse();
    }

    @Test
    @DisplayName("a remaining failure names SQLSTATE and the constraint")
    void remainingFailureNamesSqlstateAndConstraint() {
        seedAccountSiteBatch();
        insertMarkedSegment();

        DataIntegrityViolationException thrown = catchBatchDelete();

        IllegalStateException named = SeededSiteTeardown.namedFailure(
                "DELETE FROM batches WHERE site_id = " + site, thrown);

        assertThat(named.getMessage())
                .contains("DELETE FROM batches")
                .contains("SQLSTATE=23503")
                .contains("constraint=changelog_segments_batch_id_fkey");
        assertThat(named.getCause()).isSameAs(thrown);
    }

    private DataIntegrityViolationException catchBatchDelete() {
        return assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM batches WHERE site_id = ?", site));
    }

    private void seedAccountSiteBatch() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Teardown guard 265', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, account, "teardown-guard-265@" + FOREIGN_DOMAIN);
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', 'Teardown guard 265', true,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, site, account, FOREIGN_DOMAIN, FOREIGN_DOMAIN);
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 'teardown-guard-265/', 0, 0, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, batch, account, site);
    }

    /**
     * Both queue stamps are set so a sibling context's sweep worker cannot claim this row
     * ({@code findNextPendingPluginSql} / {@code findNextPendingEgress} have no site predicate).
     */
    private void insertMarkedSegment() {
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                                                record_count, content_hash, s3_key, mode,
                                                provisional, plugin_sql_at, egress_at)
                VALUES (?, ?, ?, 1, 5, 10, 'hash', ?, 'DELTA', FALSE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, segment, site, batch, "delta/" + site + "/segments/" + segment + ".pb.gz");
    }

    private void insertForeignActivation() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Teardown guard 265 activation', true,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, foreignActivationAccount, "teardown-guard-265-act@" + FOREIGN_DOMAIN);
        jdbc.update("""
                INSERT INTO account_plugins (account_id, plugin_id, plugin_data, is_active,
                                             activated_at, baseline_batch_id)
                VALUES (?, 'bit-bi', '{}'::jsonb, true, CURRENT_TIMESTAMP, ?)
                """, foreignActivationAccount, batch);
    }

    private void insertErrorLog() {
        jdbc.update("""
                INSERT INTO error_logs (id, batch_id, site_id, type, title, message, occurred_at)
                VALUES (?, ?, ?, 'TeardownGuard', 'cleanup 265', 'batch_id cascade probe', now())
                """, errorLog, batch, site);
    }

    private boolean rowExists(String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count != null && count > 0;
    }
}
