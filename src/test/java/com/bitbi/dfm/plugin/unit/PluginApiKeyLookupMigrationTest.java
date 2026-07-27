package com.bitbi.dfm.plugin.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape checks for the V42 migration that introduces the {@code api_key_lookup} column (031).
 * <p>
 * Migrations are forward-only and must stay additive: the column has to be nullable (existing
 * rows cannot be backfilled — the plaintext keys are not stored) and the lookup index has to be
 * unique and partial so multiple activations without a lookup remain legal.
 * </p>
 */
@DisplayName("V42 plugin api key lookup migration")
class PluginApiKeyLookupMigrationTest {

    private static final String MIGRATION = "db/migration/V42__plugin_api_key_lookup.sql";

    private String sql() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s must exist", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    @DisplayName("adds a nullable api_key_lookup column to account_plugins")
    void shouldAddNullableLookupColumn() throws IOException {
        String sql = sql();

        assertThat(sql).contains("alter table account_plugins");

        String addColumn = statementContaining(sql, "add column");
        assertThat(addColumn).contains("api_key_lookup");
        assertThat(addColumn)
                .as("existing rows cannot be backfilled, so the column must stay nullable")
                .doesNotContain("not null");
    }

    private String statementContaining(String sql, String needle) {
        for (String statement : sql.split(";")) {
            if (statement.contains(needle)) {
                return statement;
            }
        }
        throw new AssertionError("No SQL statement containing '" + needle + "' in " + MIGRATION);
    }

    @Test
    @DisplayName("creates a unique partial index on the lookup column")
    void shouldCreateUniquePartialIndex() throws IOException {
        String sql = sql();

        assertThat(sql).contains("create unique index");
        assertThat(sql).contains("where api_key_lookup is not null");
    }

    @Test
    @DisplayName("is additive: no data rewrite and no destructive statements")
    void shouldBeAdditiveOnly() throws IOException {
        String sql = sql();

        assertThat(sql).doesNotContain("drop ");
        assertThat(sql).doesNotContain("update ");
        assertThat(sql).doesNotContain("delete ");
    }
}
