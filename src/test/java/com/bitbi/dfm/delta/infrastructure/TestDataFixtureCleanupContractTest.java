package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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

    /** A COMPLETED batch of store-01.example.com, seeded by test-data.sql. */
    private static final UUID SEEDED_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /**
     * Deliberately not an {@code %.example.com} domain: the fixture must not be able to reach this
     * site through its own {@code site_id} predicate, which is the whole point of the guard. It also
     * means the fixture never removes it, so this class takes it away itself.
     */
    private static final String FOREIGN_DOMAIN = "cleanup-guard-226.invalid";

    private final UUID foreignAccount = UUID.randomUUID();
    private final UUID foreignSite = UUID.randomUUID();
    private final UUID strandedSegment = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void removeForeignFixtures() {
        jdbc.update("DELETE FROM changelog_segments WHERE id = ?", strandedSegment);
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

    /**
     * The row the fixture cannot see: its site is not an {@code %.example.com} one, so the
     * {@code site_id} sweep skips it, while its batch belongs to a site the fixture does delete.
     */
    private void seedForeignSiteWithSeededBatchSegment() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Cleanup guard 226', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, foreignAccount, "cleanup-guard-226@" + FOREIGN_DOMAIN);
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', 'Cleanup guard 226', true,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, foreignSite, foreignAccount, FOREIGN_DOMAIN, FOREIGN_DOMAIN);
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                                                record_count, content_hash, s3_key, mode,
                                                provisional, plugin_sql_at)
                VALUES (?, ?, ?, 1, 5, 10, 'hash', ?, 'DELTA', FALSE, CURRENT_TIMESTAMP)
                """, strandedSegment, foreignSite, SEEDED_BATCH,
                "delta/" + foreignSite + "/segments/1.pb.gz");
    }

    /**
     * Runs the fixture the way Spring's {@code @Sql} does — same script, same splitter — so the
     * guard cannot pass against a construct {@code ScriptUtils} would choke on.
     */
    private void runFixtureScript() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("test-data.sql"));
        populator.execute(dataSource);
    }

    private boolean segmentExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM changelog_segments WHERE id = ?", Integer.class, strandedSegment);
        return count != null && count > 0;
    }
}
