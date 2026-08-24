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
 * {@code test-data.sql} must survive leftover rows whose FK the fixture used to miss (issues
 * #226, #228).
 *
 * <p>#226 pinned the two non-cascading FKs onto {@code batches}: a changelog segment and an
 * {@code account_plugins} activation whose {@code site_id}/{@code account_id} the fixture does
 * not recognise, but whose {@code batch_id} it is about to delete. This class keeps those two
 * leftover-then-clear methods.
 *
 * <p>#228 is the rest of the same hole, one statement later: {@code DELETE FROM sites} and
 * {@code DELETE FROM accounts} can still fail on leftover rows the fixture does not sweep by the
 * relationship the constraint actually uses, and on rows that have no relationship path back to
 * the seed at all.
 *
 * <pre>{@code
 * DELETE FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
 * DELETE FROM sites   WHERE domain LIKE '%.example.com';
 * DELETE FROM accounts WHERE email LIKE '%@example.com';
 * }</pre>
 *
 * <p>Three independent shapes, each of which surfaces as a {@code ScriptStatementFailedException}
 * inside {@code @Sql} in whichever class runs next:
 *
 * <ol>
 *   <li>{@code batches.account_id} / {@code sites.account_id} (V3 / V2, no cascade).
 *       {@code Batch.start(accountId, siteId)} takes the two ids independently, so a batch pairing
 *       an {@code %@example.com} account with a site whose domain does not match survives the
 *       first statement and blocks the third.</li>
 *   <li>{@code device_authorizations.site_id} / {@code .account_id} (V21, no cascade). The fixture
 *       had no statement for that table, so an approved authorization pointing at a seeded site
 *       blocks {@code DELETE FROM sites}.</li>
 *   <li>Rows outside the seed identity predicates entirely ({@code *.test.local}, and an
 *       {@code %@example.com} account whose site domain is {@code {uuid}_example.com}). Sweeping
 *       every non-cascading FK by its own relationship cannot reach these: they have no path back
 *       to the seed. Widening {@code DELETE FROM sites} pulls them in, so every site-keyed
 *       statement above it has to widen in step or it blocks on the way through.</li>
 * </ol>
 *
 * <p>The leftover-then-clear shape of #119 / #159 / #226: seed exactly the row that blocks the
 * delete, run the real script, and require it to clear. Asserting only "the script runs" would
 * pass against a fixture that never had the row.
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

    /**
     * The domain shape {@code BatchRetentionIntegrationTest} builds today: an {@code %@example.com}
     * account paired with {@code {accountId}_example.com}. {@code LIKE '%.example.com'} needs a
     * literal dot, so this site is owned by a seeded account and invisible to the domain predicate.
     */
    private static final String UNDERSCORE_DOMAIN = SEEDED_ACCOUNT + "_example.com";

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
    /** Batch pairing {@link #SEEDED_ACCOUNT} with {@link #foreignSite} (issue #228 §1). */
    private final UUID mismatchedAccountBatch = UUID.randomUUID();
    private final UUID strandedDeviceAuth = UUID.randomUUID();
    private final UUID testLocalAccount = UUID.randomUUID();
    private final UUID testLocalSite = UUID.randomUUID();
    private final UUID testLocalBatch = UUID.randomUUID();
    private final UUID testLocalSegment = UUID.randomUUID();
    private final UUID underscoreSite = UUID.randomUUID();
    private final UUID underscoreBatch = UUID.randomUUID();
    private final UUID underscoreSegment = UUID.randomUUID();
    private final UUID underscoreErrorLog = UUID.randomUUID();
    private final UUID underscoreCheckpoint = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeForeignFixtures() {
        jdbc.update("DELETE FROM device_authorizations WHERE id = ?", strandedDeviceAuth);
        jdbc.update("DELETE FROM changelog_segments WHERE id IN (?, ?, ?)",
                strandedSegment, testLocalSegment, underscoreSegment);
        jdbc.update("DELETE FROM error_logs WHERE id = ?", underscoreErrorLog);
        jdbc.update("DELETE FROM checkpoints WHERE id = ?", underscoreCheckpoint);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id IN (?, ?)",
                testLocalSite, underscoreSite);
        jdbc.update("DELETE FROM account_plugins WHERE account_id IN (?, ?)",
                foreignAccount, testLocalAccount);
        jdbc.update("DELETE FROM batches WHERE id IN (?, ?, ?, ?)",
                guardBatch, mismatchedAccountBatch, testLocalBatch, underscoreBatch);
        jdbc.update("DELETE FROM sites WHERE id IN (?, ?, ?)",
                foreignSite, testLocalSite, underscoreSite);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", foreignAccount, testLocalAccount);
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

    @Test
    @DisplayName("clears a batch whose account it deletes but whose site it does not match")
    void shouldClearBatchReachableOnlyThroughItsAccount() {
        seedMismatchedAccountBatch();

        assertThatCode(this::runFixtureScript)
                .as("test-data.sql must not be blocked by batches_account_id_fkey")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", mismatchedAccountBatch))
                .as("the stranded batch must be gone, not merely survive an unblocked script")
                .isFalse();
    }

    @Test
    @DisplayName("clears a device authorization that would block DELETE FROM sites")
    void shouldClearDeviceAuthorizationThatWouldBlockSiteDelete() {
        seedDeviceAuthorizationOnSeededSite();

        assertThatCode(this::runFixtureScript)
                .as("test-data.sql must not be blocked by device_authorizations_site_id_fkey")
                .doesNotThrowAnyException();

        assertThat(rowExists(
                "SELECT count(*) FROM device_authorizations WHERE id = ?", strandedDeviceAuth))
                .as("the leftover authorization must be gone, not merely survive an unblocked script")
                .isFalse();
    }

    @Test
    @DisplayName("clears the *.test.local family the seed identity predicates cannot see")
    void shouldClearRowsOutsideTheSeedIdentityPredicates() {
        seedTestLocalFamily();

        assertThatCode(this::runFixtureScript)
                .as("test-data.sql must not leave *.test.local rows to accumulate across the run")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM accounts WHERE id = ?", testLocalAccount))
                .as("the *.test.local account must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM sites WHERE id = ?", testLocalSite))
                .as("the *.test.local site must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", testLocalBatch))
                .as("the *.test.local batch must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM changelog_segments WHERE id = ?", testLocalSegment))
                .as("the *.test.local segment must be gone")
                .isFalse();
        assertThat(rowExists(
                "SELECT count(*) FROM site_sync_state WHERE site_id = ?", testLocalSite))
                .as("the *.test.local sync state must be gone")
                .isFalse();
    }

    @Test
    @DisplayName("clears a foreign-domain site owned by a seeded account, and the site-keyed rows above it")
    void shouldClearForeignDomainSiteOwnedByASeededAccount() {
        seedUnderscoreDomainSiteOwnedBySeededAccount();

        assertThatCode(this::runFixtureScript)
                .as("widening DELETE FROM sites must not block on error_logs/checkpoints/"
                        + "site_sync_state/changelog_segments of the pulled-in site")
                .doesNotThrowAnyException();

        assertThat(rowExists("SELECT count(*) FROM sites WHERE id = ?", underscoreSite))
                .as("the {uuid}_example.com site must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM error_logs WHERE id = ?", underscoreErrorLog))
                .as("error_logs.site_id has no cascade: the row must be swept, not left to block")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM checkpoints WHERE id = ?", underscoreCheckpoint))
                .as("the pulled-in site's checkpoint must be gone")
                .isFalse();
        assertThat(rowExists(
                "SELECT count(*) FROM site_sync_state WHERE site_id = ?", underscoreSite))
                .as("the pulled-in site's sync state must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM changelog_segments WHERE id = ?", underscoreSegment))
                .as("the pulled-in site's segment (site_id arm) must be gone")
                .isFalse();
        assertThat(rowExists("SELECT count(*) FROM batches WHERE id = ?", underscoreBatch))
                .as("the pulled-in site's batch must be gone")
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
                VALUES (?, 'bit-bi', '{}'::jsonb, true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', ?)
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
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', ?, 'V2')
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
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
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
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, guardBatch, SEEDED_ACCOUNT, SEEDED_SITE);
    }

    /** Deliberately outside {@code %@example.com}, so the fixture's account sweep cannot reach it. */
    private void insertForeignAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Cleanup guard 226', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, foreignAccount, "cleanup-guard-226@" + FOREIGN_DOMAIN);
    }

    /**
     * {@code Batch.start} takes {@code accountId} and {@code siteId} independently, so a persisted
     * batch can pair a seeded account with a site the domain predicate does not match. The site
     * sweep leaves it, and {@code batches_account_id_fkey} then blocks {@code DELETE FROM accounts}.
     */
    private void seedMismatchedAccountBatch() {
        insertForeignAccount();
        insertSite(foreignSite, foreignAccount, FOREIGN_DOMAIN, "Cleanup guard 228 account");
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 'cleanup-guard-228-account/', 0, 0, false,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, mismatchedAccountBatch, SEEDED_ACCOUNT, foreignSite);
    }

    /**
     * An approved authorization pointing at a seeded site. {@code device_authorizations.site_id}
     * has no {@code ON DELETE} action (V21), and the fixture used to have no statement for the
     * table, so this row blocked {@code DELETE FROM sites}.
     */
    private void seedDeviceAuthorizationOnSeededSite() {
        jdbc.update("""
                INSERT INTO device_authorizations (id, device_code, user_code, site_name,
                                                   account_id, site_id, status, expires_at,
                                                   created_at, site_type)
                VALUES (?, ?, ?, 'cleanup-guard-228.example.com', ?, ?, 'APPROVED',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, 'DBF')
                """, strandedDeviceAuth, strandedDeviceAuth.toString(),
                userCode(strandedDeviceAuth), SEEDED_ACCOUNT, SEEDED_SITE);
    }

    /**
     * The family {@code BatchPerSessionIngestionIntegrationTest} / {@code DeltaSessionLiveness
     * IntegrationTest} / {@code BatchTerminalTransitionLockingIntegrationTest} create today:
     * {@code *.test.local} sits outside both seed identity predicates, so a general FK-by-
     * relationship rule cannot reach it.
     */
    private void seedTestLocalFamily() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Cleanup guard 228 test.local', true,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, testLocalAccount, "228-" + testLocalAccount + "@test.local");
        insertSite(testLocalSite, testLocalAccount,
                "228-" + testLocalSite + ".test.local", "Cleanup guard 228 test.local");
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 'cleanup-guard-228-test-local/', 0, 0, false,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, testLocalBatch, testLocalAccount, testLocalSite);
        insertMarkedSegment(testLocalSegment, testLocalSite, testLocalBatch);
        jdbc.update("""
                INSERT INTO site_sync_state (site_id, last_applied_seq, last_checkpoint_seq,
                                             schema_version, updated_at)
                VALUES (?, 1, 0, 1, CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, testLocalSite);
    }

    /**
     * {@code BatchRetentionIntegrationTest}'s live shape: a seeded account owns a site whose
     * domain uses an underscore where {@code LIKE '%.example.com'} needs a dot. Widening
     * {@code DELETE FROM sites} pulls it in, so every site-keyed statement above it
     * ({@code error_logs} especially — no cascade on {@code site_id}) has to widen in step.
     */
    private void seedUnderscoreDomainSiteOwnedBySeededAccount() {
        insertSite(underscoreSite, SEEDED_ACCOUNT, UNDERSCORE_DOMAIN, "Cleanup guard 228 underscore");
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 'cleanup-guard-228-underscore/', 0, 0, false,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, underscoreBatch, SEEDED_ACCOUNT, underscoreSite);
        insertMarkedSegment(underscoreSegment, underscoreSite, underscoreBatch);
        jdbc.update("""
                INSERT INTO error_logs (id, batch_id, site_id, type, title, message, occurred_at)
                VALUES (?, ?, ?, 'CleanupGuard', 'cleanup 228', 'underscore-domain leftover',
                        '2025-10-04 10:15:00')
                """, underscoreErrorLog, underscoreBatch, underscoreSite);
        jdbc.update("""
                INSERT INTO checkpoints (id, site_id, table_name, seq, row_count, created_at, updated_at)
                VALUES (?, ?, 'cleanup_228', 1, 0, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, underscoreCheckpoint, underscoreSite);
        jdbc.update("""
                INSERT INTO site_sync_state (site_id, last_applied_seq, last_checkpoint_seq,
                                             schema_version, updated_at)
                VALUES (?, 1, 0, 1, CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, underscoreSite);
    }

    private void insertSite(UUID id, UUID accountId, String domain, String displayName) {
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', ?, true,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', ?, 'V2')
                """, id, accountId, domain, displayName, domain);
    }

    /**
     * Both queue stamps are set, not just {@code plugin_sql_at}: {@code findNextPendingEgress} is
     * as global and as site-predicate-free as {@code findNextPendingPluginSql} (#175), so a pending
     * row here can be claimed {@code FOR UPDATE SKIP LOCKED} by any live cached context's egress
     * worker.
     */
    private void insertMarkedSegment(UUID id, UUID siteId, UUID batchId) {
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                                                record_count, content_hash, s3_key, mode,
                                                provisional, plugin_sql_at, egress_at)
                VALUES (?, ?, ?, 1, 5, 10, 'hash', ?, 'DELTA', FALSE,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, id, siteId, batchId, "delta/" + siteId + "/segments/" + id + ".pb.gz");
    }

    /** {@code user_code} is unique and at most 10 characters (V21). */
    private static String userCode(UUID id) {
        return id.toString().replace("-", "").substring(0, 10);
    }

    private boolean segmentExists() {
        return rowExists("SELECT count(*) FROM changelog_segments WHERE id = ?", strandedSegment);
    }

    private boolean rowExists(String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count != null && count > 0;
    }
}
