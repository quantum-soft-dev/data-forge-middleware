package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code test-data.sql} must survive a changelog segment whose site it does not recognise (issue
 * #226).
 *
 * <p>The fixture clears the suite's shared tables by {@code site_id}:
 *
 * <pre>{@code
 * DELETE FROM changelog_segments WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
 * DELETE FROM batches            WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
 * }</pre>
 *
 * <p>but the constraint that stands in the way of the second statement is
 * {@code changelog_segments_batch_id_fkey}, on <b>{@code batch_id}</b>, and it carries no
 * {@code ON DELETE CASCADE} — only {@code site_id} does. The two are the same relationship for a
 * segment written by the application, and different relationships for one written by a test: nothing
 * in {@code ChangelogSegment.create(siteId, batchId, ...)} requires the batch to belong to the site.
 * A segment whose site is not an {@code %.example.com} one therefore survives the first statement
 * and blocks the second, and the failure surfaces as a {@code ScriptStatementFailedException} in
 * whichever class happens to run next — a fixture error in an innocent test, which is what makes it
 * expensive.
 *
 * <p>The fixture already deletes {@code uploaded_files} by the batch relationship
 * ({@code batch_id IN (SELECT id FROM batches WHERE site_id IN ...)}) two statements earlier, so the
 * shape this pins is the one the file uses elsewhere for the same reason.
 *
 * <p>The leftover-then-clear shape of #119 and #159: seed exactly the row that blocks the delete,
 * run the real script, and require it to clear. Asserting only "the script runs" would pass against
 * a fixture that never had the row.
 */
@DisplayName("test-data.sql fixture cleanup")
class TestDataFixtureCleanupContractTest extends BaseIntegrationTest {

    /** store-01.example.com, seeded by test-data.sql -- a site the fixture deletes. */
    private static final UUID SEEDED_SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** The account owning {@link #SEEDED_SITE}, seeded by test-data.sql. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /**
     * Deliberately not an {@code %.example.com} domain: the fixture must not be able to reach this
     * site through its own {@code site_id} predicate, which is the whole point of the guard. It also
     * means the fixture never removes it, so this class takes it away itself.
     */
    private static final String FOREIGN_DOMAIN = "cleanup-guard-226.invalid";

    private final UUID foreignAccount = UUID.randomUUID();
    private final UUID foreignSite = UUID.randomUUID();
    private final UUID strandedSegment = UUID.randomUUID();
    /**
     * The guard's own batch under {@link #SEEDED_SITE}, rather than one of the seeded batches.
     * It has to belong to a site the fixture deletes -- that is what makes the delete fire the
     * constraint -- but it must not be a batch other classes read: the batch-keyed queries have no
     * site predicate either ({@code findByBatchIdOrderByFirstSeq},
     * {@code SqlGenerationPersistence.loadBatchData}, {@code BatchHistoryService}), so hanging the
     * stranded segment off store-01's flagship COMPLETED batch would let a batch-parquet build
     * replay a segment whose object was never uploaded and fail an artifact for a batch this class
     * does not own. Nothing knows this id, so nothing reads it.
     */
    private final UUID guardBatch = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeForeignFixtures() {
        jdbc.update("DELETE FROM changelog_segments WHERE id = ?", strandedSegment);
        jdbc.update("DELETE FROM account_plugins WHERE account_id = ?", foreignAccount);
        jdbc.update("DELETE FROM batches WHERE id = ?", guardBatch);
        jdbc.update("DELETE FROM sites WHERE id = ?", foreignSite);
        jdbc.update("DELETE FROM accounts WHERE id = ?", foreignAccount);
    }

    @Test
    @DisplayName("clears a segment whose batch it deletes but whose site it does not match")
    void shouldClearSegmentReachableOnlyThroughItsBatch() {
        seedForeignSiteWithSeededBatchSegment();

        assertThatCode(this::runFixtureScript)
                .as("test-data.sql must not be blocked by changelog_segments_batch_id_fkey")
                .doesNotThrowAnyException();

        assertThat(segmentExists())
                .as("the stranded segment must be gone, not merely survive an unblocked script")
                .isFalse();
    }

    @Test
    @DisplayName("clears an activation whose baseline batch it deletes but whose account it does not match")
    void shouldClearActivationReachableOnlyThroughItsBaselineBatch() {
        seedForeignAccountWithSeededBaselineBatch();

        assertThatCode(this::runFixtureScript)
                .as("test-data.sql must not be blocked by fk_account_plugins_baseline_batch")
                .doesNotThrowAnyException();

        assertThat(activationExists())
                .as("the stranded activation must be gone, not merely survive an unblocked script")
                .isFalse();
    }

    /**
     * The sibling of the segment above, and the only other foreign key to {@code batches} without a
     * cascade: {@code fk_account_plugins_baseline_batch} is {@code ON DELETE RESTRICT} (V25), while
     * the fixture sweeps {@code account_plugins} by <em>account</em>. An activation owned by an
     * account outside {@code %@example.com} whose baseline batch the fixture deletes therefore
     * blocks the same statement, one constraint over.
     */
    private void seedForeignAccountWithSeededBaselineBatch() {
        insertForeignAccount();
        insertGuardBatch();
        jdbc.update("""
                INSERT INTO account_plugins (account_id, plugin_id, plugin_data, is_active,
                                             activated_at, baseline_batch_id)
                VALUES (?, 'bit-bi', '{}'::jsonb, true, CURRENT_TIMESTAMP, ?)
                """, foreignAccount, guardBatch);
    }

    private boolean activationExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM account_plugins WHERE account_id = ?", Integer.class, foreignAccount);
        return count != null && count > 0;
    }

    /**
     * The row the fixture cannot see: its site is not an {@code %.example.com} one, so the
     * {@code site_id} sweep skips it, while its batch belongs to a site the fixture does delete.
     */
    private void seedForeignSiteWithSeededBatchSegment() {
        insertForeignAccount();
        insertGuardBatch();
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', 'Cleanup guard 226', true,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, foreignSite, foreignAccount, FOREIGN_DOMAIN, FOREIGN_DOMAIN);
        // Both queue stamps are set, not just plugin_sql_at: findNextPendingEgress is as global and
        // as site-predicate-free as findNextPendingPluginSql (#175), so a pending row here can be
        // claimed FOR UPDATE SKIP LOCKED by any live cached context's egress worker, which would
        // then try to download an object that was never uploaded and hold a row lock on the very
        // row runFixtureScript() is about to delete -- surfacing under the 30 s lock_timeout of
        // #197 as a 55P03 this class would blame on changelog_segments_batch_id_fkey.
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                                                record_count, content_hash, s3_key, mode,
                                                provisional, plugin_sql_at, egress_at)
                VALUES (?, ?, ?, 1, 5, 10, 'hash', ?, 'DELTA', FALSE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, strandedSegment, foreignSite, guardBatch,
                "delta/" + foreignSite + "/segments/1.pb.gz");
    }

    /**
     * Runs the fixture the way Spring's {@code @Sql} does — same script, same splitter, and the same
     * all-or-nothing outcome.
     *
     * <p>The transaction is the half that is easy to miss. {@code @Sql} defaults to
     * {@code transactionMode = INFERRED}, so with a {@code PlatformTransactionManager} in the context
     * the script runs in a transaction that rolls back when a statement fails, whereas
     * {@code ResourceDatabasePopulator.execute(DataSource)} auto-commits statement by statement. Left
     * that way, a future regression of the sweep would commit every {@code DELETE} and never reach
     * the {@code INSERT}s — stripping the shared database of the whole {@code %.example.com} fixture
     * and failing the classes that carry no {@code @Sql} of their own, for a reason that has nothing
     * to do with them. That is the exact failure this ticket exists to remove, so the guard must not
     * be able to cause it.
     */
    private void runFixtureScript() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("test-data.sql"));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> populator.execute(dataSource));
    }

    /**
     * A batch of {@link #SEEDED_SITE}, so the fixture's {@code DELETE FROM batches} sweep reaches it
     * -- which is what makes the guard's row block that statement -- while no other class knows the
     * id. See {@link #guardBatch}.
     */
    private void insertGuardBatch() {
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 'cleanup-guard-226/', 0, 0, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, guardBatch, SEEDED_ACCOUNT, SEEDED_SITE);
    }

    /** Deliberately outside {@code %@example.com}, so the fixture's account sweep cannot reach it. */
    private void insertForeignAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Cleanup guard 226', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, foreignAccount, "cleanup-guard-226@" + FOREIGN_DOMAIN);
    }

    private boolean segmentExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM changelog_segments WHERE id = ?", Integer.class, strandedSegment);
        return count != null && count > 0;
    }
}
