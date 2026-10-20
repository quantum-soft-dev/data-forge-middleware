package com.bitbi.dfm.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V61 stamps the batches of each site's current epoch with {@code site_sync_state.baseline_epoch}
 * and leaves the discarded history unstamped (issue #388).
 * <p>
 * Run against a database of its own, the {@code BatchTotalsFromArtifactsMigrationIntegrationTest}
 * shape: V61's backfill is a statement over every batch, and run on the shared test database it
 * would rewrite and lock rows other classes own.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V61 batch baseline epoch backfill (#388)")
class BatchBaselineEpochMigrationIntegrationTest {

    private static final String LOCATION = "classpath:db/migration";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 8, 0);

    private final String database = "v61_migration_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID account = UUID.randomUUID();

    // Site A, at epoch 4: a re-baseline (a2) with history before and after it.
    private final UUID siteA = UUID.randomUUID();
    private final UUID beforeTheSnapshot = UUID.randomUUID();
    private final UUID theSnapshot = UUID.randomUUID();
    private final UUID afterTheSnapshot = UUID.randomUUID();
    private final UUID aFailedSnapshot = UUID.randomUUID();
    private final UUID aTimedOutSessionWithSeals = UUID.randomUUID();
    private final UUID anEmptySession = UUID.randomUUID();
    private final UUID aRunningSession = UUID.randomUUID();
    // Site B, at epoch 0, never re-baselined: everything is current.
    private final UUID siteB = UUID.randomUUID();
    private final UUID onlyDeltas = UUID.randomUUID();
    // Site C has batches and no sync state.
    private final UUID siteC = UUID.randomUUID();
    private final UUID noSyncState = UUID.randomUUID();

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String url;
    private String user;
    private String password;

    @BeforeAll
    void migrateSeedAndMigrate() {
        connect();
        admin.execute("CREATE DATABASE " + database);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrlOfOwnDatabase(), user, password));

        migrateTo("60");
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'V61 migration', true, ?, ?)
                """, account, "v61-" + account + "@example.com", T0, T0);
        site(siteA, 4L);
        site(siteB, 0L);
        site(siteC, null);

        batch(beforeTheSnapshot, siteA, "DELTA", "COMPLETED", 1, true);
        batch(theSnapshot, siteA, "FULL_SNAPSHOT", "COMPLETED", 2, true);
        batch(afterTheSnapshot, siteA, "DELTA", "COMPLETED", 3, true);
        // Never committed, so it never reset anything: it does not end the epoch.
        batch(aFailedSnapshot, siteA, "FULL_SNAPSHOT", "FAILED", 4, false);
        batch(aTimedOutSessionWithSeals, siteA, "CONTINUOUS", "NOT_COMPLETED", 5, true);
        batch(anEmptySession, siteA, "DELTA", "COMPLETED", 6, false);
        batch(aRunningSession, siteA, "DELTA", "IN_PROGRESS", 7, true);
        batch(onlyDeltas, siteB, "DELTA", "COMPLETED", 1, true);
        batch(noSyncState, siteC, "DELTA", "COMPLETED", 1, true);

        migrateTo("61");
    }

    @AfterAll
    void dropDatabase() {
        if (admin != null) {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    @DisplayName("the completed snapshot and every recorded batch after it carry the site's epoch")
    void theCurrentEpochIsStamped() {
        assertThat(epoch(theSnapshot)).isEqualTo(4L);
        assertThat(epoch(afterTheSnapshot)).isEqualTo(4L);
        assertThat(epoch(aTimedOutSessionWithSeals))
                .as("its seals were committed, so its records are part of the epoch")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("a failed snapshot does not end the epoch, and is itself not stamped (it recorded nothing)")
    void aFailedSnapshotIsNotAReset() {
        assertThat(epoch(afterTheSnapshot)).isEqualTo(4L);
        assertThat(epoch(aFailedSnapshot)).isNull();
    }

    @Test
    @DisplayName("a batch before the latest completed snapshot belongs to a discarded history and stays NULL")
    void theDiscardedHistoryIsLeftUnstamped() {
        assertThat(epoch(beforeTheSnapshot)).isNull();
    }

    @Test
    @DisplayName("an empty session and a running one stay NULL")
    void unrecordedAndRunningBatchesStayNull() {
        assertThat(epoch(anEmptySession)).isNull();
        assertThat(epoch(aRunningSession)).isNull();
    }

    @Test
    @DisplayName("a site never re-baselined has every batch in its epoch; a site without sync state has none")
    void otherSites() {
        assertThat(epoch(onlyDeltas)).isEqualTo(0L);
        assertThat(epoch(noSyncState)).isNull();
    }

    @Test
    @DisplayName("the seq predicate's index exists")
    void theIndexExists() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_batches_site_last_seq'", Long.class))
                .isEqualTo(1L);
    }

    private Long epoch(UUID batch) {
        return jdbc.queryForObject("SELECT baseline_epoch FROM batches WHERE id = ?", Long.class, batch);
    }

    private void site(UUID site, Long baselineEpoch) {
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, display_name, is_active, created_at, updated_at,
                                   site_name, client_api_version)
                VALUES (?, ?, ?, 'V61 migration', true, ?, ?, ?, 'V2')
                """, site, account, site + ".example.com", T0, T0, site + ".example.com");
        if (baselineEpoch != null) {
            jdbc.update("INSERT INTO site_sync_state (site_id, baseline_epoch) VALUES (?, ?)", site, baselineEpoch);
        }
    }

    private void batch(UUID batch, UUID site, String mode, String status, int hour, boolean recorded) {
        LocalDateTime startedAt = T0.plusHours(hour);
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at,
                                     session_mode, first_seq, last_seq)
                VALUES (?, ?, ?, ?, 'v61/', 0, 0, false, ?, ?, ?, ?, ?, ?)
                """, batch, account, site, status, startedAt, startedAt, startedAt.plusMinutes(5), mode,
                recorded ? hour * 100L : null, recorded ? hour * 100L + 99 : null);
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(jdbcUrlOfOwnDatabase(), user, password)
                .locations(LOCATION)
                .target(version)
                .load()
                .migrate();
    }

    private String jdbcUrlOfOwnDatabase() {
        return url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private void connect() {
        TestContainersManager containers = TestContainersManager.getInstance();
        if (containers.isUsingExternalServices()) {
            url = "jdbc:postgresql://localhost:5432/dataforge_test";
            user = "dataforge";
            password = "dataforge_test_password";
        } else {
            PostgreSQLContainer postgres = containers.getPostgresContainer();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }
}
