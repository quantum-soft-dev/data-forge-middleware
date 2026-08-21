package com.bitbi.dfm.delta.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared-database teardown for tests that seed their own sites and accounts.
 *
 * <p>#226/#228 taught the fixture to sweep the two non-cascade FKs onto {@code batches} by the
 * relationship each constraint actually uses. That is necessary and not sufficient: a writer
 * the test started — a gRPC ingestion commit still in flight, or a sibling context's worker —
 * can insert a new referencing row <em>between</em> those statements, and
 * {@code DELETE FROM batches} then fails as an opaque {@code DataIntegrityViolationException}
 * (issue #265).
 *
 * <p>The two constraints that can still block that statement:
 * <ul>
 *   <li>{@code changelog_segments_batch_id_fkey} (V30, no {@code ON DELETE} action)</li>
 *   <li>{@code fk_account_plugins_baseline_batch} (V25, {@code ON DELETE RESTRICT})</li>
 * </ul>
 * {@code error_logs.batch_id} used to be a third (V5) but V22 added {@code ON DELETE CASCADE}.
 *
 * <p>Shape: re-sweep those two relationships and retry the delete once. A remaining failure
 * names {@code SQLSTATE} and the constraint, so the next occurrence is itself.
 */
public final class SeededSiteTeardown {

    private static final Logger log = LoggerFactory.getLogger(SeededSiteTeardown.class);

    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([^\"]+)\"");

    private SeededSiteTeardown() {
    }

    public static void cleanSite(JdbcTemplate jdbc, UUID siteId) {
        cleanSite(jdbc, siteId, () -> {
        });
    }

    /**
     * {@code betweenSweepAndBatchDelete} is the race injector the tests use. Production teardown
     * passes a no-op; the retry still re-sweeps, so a row that lands in that window is cleared.
     */
    static void cleanSite(JdbcTemplate jdbc, UUID siteId, Runnable betweenSweepAndBatchDelete) {
        try {
            deleteSiteRows(jdbc, siteId, betweenSweepAndBatchDelete);
        } catch (DataIntegrityViolationException first) {
            log.warn("retrying site teardown for {} after {}", siteId, describeFkFailure(first));
            try {
                deleteSiteRows(jdbc, siteId, () -> {
                });
            } catch (DataIntegrityViolationException second) {
                throw namedFailure("cleanSite(" + siteId + ")", second);
            }
        }
    }

    public static void cleanAccount(JdbcTemplate jdbc, UUID accountId) {
        try {
            deleteAccountRows(jdbc, accountId);
        } catch (DataIntegrityViolationException first) {
            log.warn("retrying account teardown for {} after {}", accountId, describeFkFailure(first));
            try {
                deleteAccountRows(jdbc, accountId);
            } catch (DataIntegrityViolationException second) {
                throw namedFailure("cleanAccount(" + accountId + ")", second);
            }
        }
    }

    static String describeFkFailure(Throwable error) {
        String sqlState = null;
        String sqlMessage = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sqlState == null && sql.getSQLState() != null) {
                    sqlState = sql.getSQLState();
                }
                if (sqlMessage == null && sql.getMessage() != null) {
                    sqlMessage = sql.getMessage();
                }
            }
        }
        if (sqlState == null && sqlMessage == null) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        String constraint = null;
        if (sqlMessage != null) {
            Matcher matcher = CONSTRAINT.matcher(sqlMessage);
            if (matcher.find()) {
                constraint = matcher.group(1);
            }
        }
        return "SQLSTATE=" + sqlState + " constraint=" + constraint + ": " + sqlMessage;
    }

    static IllegalStateException namedFailure(String statement, DataIntegrityViolationException error) {
        return new IllegalStateException(
                "teardown " + statement + " failed: " + describeFkFailure(error), error);
    }

    private static void deleteSiteRows(JdbcTemplate jdbc, UUID siteId, Runnable betweenSweepAndBatchDelete) {
        jdbc.update("DELETE FROM checkpoints WHERE site_id = ?", siteId);
        // Both relationships, not just site_id (issue #226): changelog_segments_batch_id_fkey
        // carries no cascade, and a segment's batch need not belong to the segment's site.
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ? OR batch_id IN "
                + "(SELECT id FROM batches WHERE site_id = ?)", siteId, siteId);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", siteId);
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", siteId);
        // The second non-cascading reference to batches: fk_account_plugins_baseline_batch is
        // ON DELETE RESTRICT (V25).
        jdbc.update("DELETE FROM account_plugins WHERE baseline_batch_id IN "
                + "(SELECT id FROM batches WHERE site_id = ?)", siteId);
        jdbc.update("DELETE FROM device_authorizations WHERE site_id = ?", siteId);
        betweenSweepAndBatchDelete.run();
        jdbc.update("DELETE FROM batches WHERE site_id = ?", siteId);
        jdbc.update("DELETE FROM sites WHERE id = ?", siteId);
    }

    private static void deleteAccountRows(JdbcTemplate jdbc, UUID accountId) {
        jdbc.update("DELETE FROM device_authorizations WHERE account_id = ? OR site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?)", accountId, accountId);
        jdbc.update("DELETE FROM error_logs WHERE site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?)", accountId);
        jdbc.update("DELETE FROM changelog_segments WHERE site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?) OR batch_id IN "
                + "(SELECT id FROM batches WHERE account_id = ? OR site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?))",
                accountId, accountId, accountId);
        jdbc.update("DELETE FROM account_plugins WHERE baseline_batch_id IN "
                + "(SELECT id FROM batches WHERE account_id = ? OR site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?))", accountId, accountId);
        jdbc.update("DELETE FROM batches WHERE account_id = ? OR site_id IN "
                + "(SELECT id FROM sites WHERE account_id = ?)", accountId, accountId);
        jdbc.update("DELETE FROM sites WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
    }
}
