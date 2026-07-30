package com.bitbi.dfm.delta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape checks for the V46 migration introducing {@code changelog_segments.provisional} (033).
 * <p>
 * A provisional segment belongs to a re-baseline snapshot that has not finished streaming yet: it is
 * durable but must stay invisible to the checkpoint fold, the delta-Parquet egress queue and the
 * Bit BI SQL queue until {@code SessionEnd} flips it. Every pre-existing segment is by definition a
 * committed baseline, so the column must default to {@code FALSE} and the migration must not rewrite
 * or drop anything.
 * </p>
 */
@DisplayName("V46 changelog segment provisional migration")
class SegmentProvisionalMigrationTest {

    private static final String MIGRATION = "db/migration/V46__changelog_segments_provisional.sql";

    private String sql() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s must exist", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    @DisplayName("adds a NOT NULL provisional column defaulting to false")
    void shouldAddNotNullProvisionalColumnDefaultingFalse() throws IOException {
        String addColumn = statementContaining(sql(), "add column");

        assertThat(addColumn).contains("changelog_segments").contains("provisional").contains("boolean");
        assertThat(addColumn)
                .as("existing segments are committed baselines — the default must make them visible")
                .contains("not null")
                .contains("default false");
    }

    @Test
    @DisplayName("enforces (site_id, first_seq) uniqueness over published rows only")
    void shouldScopeUniquenessToPublishedRows() throws IOException {
        String sql = sql();

        assertThat(sql)
                .as("the all-rows table constraint must go: a provisional segment competed for slots "
                        + "with the baseline it is replacing, which is only deleted at SessionEnd")
                .contains("drop constraint uk_segment_site_first_seq");

        String uniqueIndex = statementContaining(sql, "create unique index");
        assertThat(uniqueIndex).contains("site_id, first_seq");
        assertThat(uniqueIndex)
                .as("uniqueness must be partial on published rows")
                .contains("where provisional = false");
    }

    @Test
    @DisplayName("leaves the queue pending indexes alone — provisional rows park outside them")
    void shouldNotTouchTheQueuePendingIndexes() throws IOException {
        String sql = sql();

        assertThat(sql)
                .as("a replica on the previous release has no notion of `provisional`; visibility is "
                        + "achieved by parking egress_at/plugin_sql_at, not by changing its indexes")
                .doesNotContain("idx_changelog_segments_egress_pending")
                .doesNotContain("idx_changelog_segments_plugin_sql_pending");
    }

    @Test
    @DisplayName("is forward-only and additive")
    void shouldBeForwardOnlyAndAdditive() throws IOException {
        String sql = sql();

        assertThat(sql)
                .as("a re-baseline in flight must not be destroyed by deploying this migration")
                .doesNotContain("drop table")
                .doesNotContain("drop column")
                .doesNotContain("delete from")
                .doesNotContain("truncate");
    }

    private String statementContaining(String sql, String needle) {
        for (String statement : sql.split(";")) {
            if (statement.contains(needle)) {
                return statement;
            }
        }
        throw new AssertionError("No SQL statement containing '" + needle + "' in " + MIGRATION);
    }
}
