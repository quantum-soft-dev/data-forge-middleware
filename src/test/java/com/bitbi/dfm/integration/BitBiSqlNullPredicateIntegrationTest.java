package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ValueMapper;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.plugin.application.SqlStatementGenerator;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL handed to Bit BI, applied to a real PostgreSQL table: a DELETE or UPDATE whose key holds
 * a NULL must address the row (issue #370). The unit tests pin the text ({@code col IS NULL}); this
 * class pins the semantics, which is the part that was wrong — {@code col = NULL} is valid SQL,
 * applies without error and matches nothing, so no text-level assertion shows a mirror silently
 * keeping a row its source deleted.
 *
 * <p>Delta values go through {@link ValueMapper#toMap}, the conversion
 * {@code DeltaSqlGenerationStrategy} applies to a record's wire maps, so an {@code is_null} cell
 * reaches {@link SqlStatementGenerator} as the Java {@code null} it sees in production; the
 * strategy's own mapping of such a key is pinned by {@code DeltaSqlGenerationStrategyTest}.</p>
 *
 * <p>No Spring context: the statements run against session-private {@code TEMP} tables on one
 * connection of the shared test PostgreSQL, so nothing this class creates is visible to another
 * class or survives the method.</p>
 */
@DisplayName("Bit BI SQL: a NULL in a WHERE clause addresses the row (issue #370)")
class BitBiSqlNullPredicateIntegrationTest {

    private final SqlStatementGenerator generator = new SqlStatementGenerator();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void connect() {
        TestContainersManager containers = TestContainersManager.getInstance();
        if (containers.isUsingExternalServices()) {
            // The CI service container, the same credentials AbstractIntegrationTest registers.
            dataSource = new SingleConnectionDataSource(
                    "jdbc:postgresql://localhost:5432/dataforge_test", "dataforge", "dataforge_test_password", true);
        } else {
            PostgreSQLContainer postgres = containers.getPostgresContainer();
            dataSource = new SingleConnectionDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void disconnect() {
        // Closing the one physical connection drops every TEMP table the method created.
        dataSource.destroy();
    }

    @Test
    @DisplayName("a keyless DELETE whose row holds a NULL removes that row and only that row")
    void keylessDeleteWithNullColumnRemovesTheRow() {
        jdbc.execute("CREATE TEMP TABLE people (name varchar(50), born date)");
        Map<String, Value> alice = ordered("name", str("Alice"), "born", sqlNull());
        Map<String, Value> bob = ordered("name", str("Bob"), "born", str("2020-01-01"));

        apply(JsonlChangeRecord.OP_INSERT, null, alice, "people");
        apply(JsonlChangeRecord.OP_INSERT, null, bob, "people");
        assertThat(count("SELECT count(*) FROM people")).isEqualTo(2);

        // A keyless table: the client sends the whole row as the key.
        apply(JsonlChangeRecord.OP_DELETE, alice, null, "people");

        assertThat(count("SELECT count(*) FROM people WHERE name = 'Alice'"))
                .as("the DELETE carrying a NULL born must address the row it names")
                .isZero();
        assertThat(count("SELECT count(*) FROM people WHERE name = 'Bob'"))
                .as("IS NULL must not widen the match to a row whose born is set")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an UPDATE and a DELETE keyed on a nullable unique key column reach the row")
    void updateAndDeleteOnNullableUniqueKeyReachTheRow() {
        jdbc.execute("CREATE TEMP TABLE items (code varchar(10) NOT NULL, branch varchar(10), note varchar(20))");
        Map<String, Value> key = ordered("code", str("A1"), "branch", sqlNull());
        Map<String, Value> row = ordered("code", str("A1"), "branch", sqlNull(), "note", str("old"));

        apply(JsonlChangeRecord.OP_INSERT, null, row, "items");
        apply(JsonlChangeRecord.OP_UPDATE, key, ordered("note", str("new")), "items");

        assertThat(jdbc.queryForObject("SELECT note FROM items WHERE code = 'A1'", String.class))
                .as("the UPDATE keyed on a NULL branch must change the row")
                .isEqualTo("new");

        apply(JsonlChangeRecord.OP_DELETE, key, null, "items");

        assertThat(count("SELECT count(*) FROM items")).isZero();
    }

    @Test
    @DisplayName("a DBF DELETE with an empty date cell removes the row its INSERT created")
    void dbfDeleteWithEmptyDateCellRemovesTheRow() {
        jdbc.execute("CREATE TEMP TABLE stock (id integer, arrived date, qty integer)");
        Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "arrived", DbfColumnType.DATE,
                "qty", DbfColumnType.INTEGER);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", "1");
        row.put("arrived", "");
        row.put("qty", "");

        jdbc.execute(generator.generate(CsvRowDiff.added(2, row), "stock", types));
        assertThat(count("SELECT count(*) FROM stock")).isEqualTo(1);

        jdbc.execute(generator.generate(CsvRowDiff.deleted(2, row), "stock", types));

        assertThat(count("SELECT count(*) FROM stock"))
                .as("the DELETE for a row with an empty date must address it")
                .isZero();
    }

    private void apply(String op, Map<String, Value> key, Map<String, Value> data, String table) {
        JsonlChangeRecord record = new JsonlChangeRecord(op,
                key == null ? null : ValueMapper.toMap(key),
                data == null ? null : ValueMapper.toMap(data),
                1);
        jdbc.execute(generator.generateFromJsonl(record, table));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? -1 : value;
    }

    private static Value str(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }

    private static Value sqlNull() {
        return Value.newBuilder().setIsNull(true).build();
    }

    private static Map<String, Value> ordered(Object... pairs) {
        Map<String, Value> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Value) pairs[i + 1]);
        }
        return map;
    }
}
