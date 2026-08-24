package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeltaSqlGenerationStrategy} (026-bitbi-delta-sql, T4).
 */
@DisplayName("DeltaSqlGenerationStrategy")
class DeltaSqlGenerationStrategyTest {

    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID SITE = UUID.randomUUID();

    private ChangelogSegmentService segmentService;
    private DeltaSqlGenerationStrategy strategy;

    private final Map<String, TableSchema> schemas = Map.of(
            "customers", schema("id", "name", "email"),
            "orders", schema("id", "total"));

    @BeforeEach
    void setUp() {
        segmentService = mock(ChangelogSegmentService.class);
        strategy = new DeltaSqlGenerationStrategy(segmentService, new SqlStatementGenerator(), new SimpleMeterRegistry());
    }

    private static TableSchema schema(String... columns) {
        return new TableSchema(
                java.util.Arrays.stream(columns)
                        .map(c -> new TableSchema.ColumnDefinition(c, "varchar", true))
                        .toList(),
                List.of("id"),
                List.of());
    }

    private static ChangelogSegment segment(long firstSeq, long lastSeq, String mode) {
        return ChangelogSegment.create(SITE, BATCH, firstSeq, lastSeq, lastSeq - firstSeq + 1,
                "hash", "delta/key/" + firstSeq, mode, Map.of());
    }

    private static Value str(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value dbl(double v) {
        return Value.newBuilder().setDoubleValue(v).build();
    }

    private static ChangeRecord record(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        ChangeRecord.Builder builder = ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq);
        if (key != null) builder.putAllKey(key);
        if (data != null) builder.putAllData(data);
        return builder.build();
    }

    @Test
    @DisplayName("should map INSERT/UPDATE/DELETE to SQL with key merged into INSERT data")
    void shouldMapOpsToSql() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 1, Map.of("id", intVal(1)), Map.of("name", str("Alice"))),
                record("customers", Op.UPDATE, 2, Map.of("id", intVal(2)), Map.of("name", str("Bob"))),
                record("customers", Op.DELETE, 3, Map.of("id", intVal(3)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 3, "DELTA")), schemas, Map.of());

        assertThat(result).isNotNull();
        String sql = result.sqlContent();
        assertThat(sql).contains("INSERT INTO customers");
        assertThat(sql).contains("id").contains("'Alice'");
        assertThat(sql).contains("UPDATE customers SET name = 'Bob' WHERE id = 2");
        assertThat(sql).contains("DELETE FROM customers WHERE id = 3");
        assertThat(result.stats().inserts()).isEqualTo(1);
        assertThat(result.stats().updates()).isEqualTo(1);
        assertThat(result.stats().deletes()).isEqualTo(1);
        assertThat(result.stats().filesProcessed()).isEqualTo(1);
    }

    /**
     * A key column this pipeline cannot represent addresses no row. Rendered as SQL the WHERE clause
     * becomes {@code col = NULL}, which is never true, so the statement would be emitted, applied,
     * match nothing, and leave the Bit BI mirror silently diverged — worse than the throw #215
     * removed, because that at least was loud. The record is skipped instead (review round 1).
     *
     * <p>{@code NaN} is a usable primary key at the source: PostgreSQL compares it equal to itself.</p>
     */
    @Test
    @DisplayName("should skip a record whose key holds a decimal it cannot represent")
    void shouldSkipRecordWithUnrepresentableKey() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.DELETE, 1,
                        Map.of("id", Value.newBuilder().setDecimalValue("NaN").build()), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 1, "DELTA")),
                schemas, Map.of());

        // The strategy answers null when a batch yields no statements at all, which is what a
        // batch whose only record was skipped produces -- and is the point: no DELETE is emitted,
        // so nothing claims to address a row the key cannot identify, and no `= NULL` predicate
        // reaches the mirror.
        assertThat(result)
                .as("the record is skipped, so this batch produces no SQL rather than SQL that "
                        + "matches nothing")
                .isNull();
    }

    @Test
    @DisplayName("should carry the record seq in the statement terminator")
    void shouldCarrySeqInTerminator() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 41, Map.of("id", intVal(1)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(41, 41, "DELTA")), schemas, Map.of());

        assertThat(result.sqlContent()).contains("customers.csv:41");
    }

    @Test
    @DisplayName("should filter records at or below the table baseline seq")
    void shouldFilterByBaselineSeq() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 10, Map.of("id", intVal(1)), Map.of()),
                record("customers", Op.INSERT, 11, Map.of("id", intVal(2)), Map.of()),
                record("orders", Op.INSERT, 10, Map.of("id", intVal(3)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(10, 11, "DELTA")),
                schemas, Map.of("customers", 10L));

        // customers@10 excluded (boundary), customers@11 included, orders@10 included (no baseline row → 0)
        assertThat(result.stats().inserts()).isEqualTo(2);
        assertThat(result.sqlContent()).doesNotContain("(1)");
        assertThat(result.sqlContent()).contains("INSERT INTO orders");
    }

    @Test
    @DisplayName("should suspend a table entirely when baseline is MAX_VALUE")
    void shouldSuspendTableAtMaxBaseline() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 100, Map.of("id", intVal(1)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(100, 100, "DELTA")),
                schemas, Map.of("customers", Long.MAX_VALUE));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should skip records of tables without a declared schema")
    void shouldSkipRecordsWithoutSchema() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("unknown_table", Op.INSERT, 1, Map.of("id", intVal(1)), Map.of()),
                record("customers", Op.INSERT, 2, Map.of("id", intVal(2)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 2, "DELTA")), schemas, Map.of());

        assertThat(result.stats().inserts()).isEqualTo(1);
        assertThat(result.sqlContent()).doesNotContain("unknown_table");
    }

    @Test
    @DisplayName("should filter unknown data columns against the schema")
    void shouldFilterUnknownColumns() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 1, Map.of("id", intVal(1)),
                        Map.of("name", str("Alice"), "ghost_column", str("boo")))));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 1, "DELTA")), schemas, Map.of());

        assertThat(result.sqlContent()).doesNotContain("ghost_column");
        assertThat(result.sqlContent()).contains("'Alice'");
    }

    @Test
    @DisplayName("should skip an UPDATE whose data is empty after column filtering")
    void shouldSkipUpdateEmptyAfterFilter() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.UPDATE, 1, Map.of("id", intVal(1)), Map.of("ghost", str("x"))),
                record("customers", Op.INSERT, 2, Map.of("id", intVal(2)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 2, "DELTA")), schemas, Map.of());

        assertThat(result.stats().updates()).isZero();
        assertThat(result.stats().inserts()).isEqualTo(1);
    }

    @Test
    @DisplayName("should emit nothing for FULL_SNAPSHOT segments")
    void shouldSkipFullSnapshotSegments() throws IOException {
        SqlGenerationResult result = strategy.generate(BATCH, SITE,
                List.of(segment(1, 100, "FULL_SNAPSHOT")), schemas, Map.of());

        assertThat(result).isNull();
        org.mockito.Mockito.verifyNoInteractions(segmentService);
    }

    @Test
    @DisplayName("should render typed values: decimal unquoted plain, bytea hex, null")
    void shouldRenderTypedValues() throws IOException {
        Map<String, Value> data = Map.of(
                "name", Value.newBuilder().setDecimalValue("1E-2").build(),
                "email", Value.newBuilder().setBytesValue(ByteString.copyFrom(new byte[]{(byte) 0xAB})).build());
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("customers", Op.INSERT, 1,
                        Map.of("id", Value.newBuilder().setIsNull(true).build()), data)));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 1, "DELTA")), schemas, Map.of());

        assertThat(result.sqlContent()).contains("0.01");
        assertThat(result.sqlContent()).contains("'\\xab'");
        assertThat(result.sqlContent()).contains("NULL");
        assertThat(new BigDecimal("1E-2").toPlainString()).isEqualTo("0.01"); // sanity
    }

    @Test
    @DisplayName("should return null when no records survive filtering")
    void shouldReturnNullWhenNothingGenerated() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of());

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 1, "DELTA")), schemas, Map.of());

        assertThat(result).isNull();
    }

    /**
     * The {@code double_value} sibling of the decimal case above, and it settles the opposite way
     * (issue #233). PostgreSQL {@code real} / {@code double precision} hold {@code NaN} and
     * {@code +/-Infinity}, Parquet DOUBLE carries them natively, and PostgreSQL compares
     * {@code NaN} equal to itself — so the value is representable end to end and the record is
     * rendered rather than skipped. What it needs is the quoting: bare, {@code NaN} is an
     * identifier and the whole SQL file fails when Bit BI applies it.
     */
    @Test
    @DisplayName("should render a non-finite double as a quoted literal instead of skipping it")
    void shouldRenderNonFiniteDoubleAsQuotedLiteral() throws IOException {
        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("orders", Op.UPDATE, 1, Map.of("id", intVal(1)),
                        Map.of("total", dbl(Double.NaN))),
                record("orders", Op.DELETE, 2,
                        Map.of("id", dbl(Double.NEGATIVE_INFINITY)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 2, "DELTA")),
                schemas, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.sqlContent())
                .contains("UPDATE orders SET total = 'NaN' WHERE id = 1")
                .contains("DELETE FROM orders WHERE id = '-Infinity'");
        assertThat(result.stats().updates()).isEqualTo(1);
        assertThat(result.stats().deletes()).isEqualTo(1);
    }

    /**
     * The declared type decides where the wire case cannot (issue #233, review round 3). A
     * {@code numeric(p,s)} column materialises as a Parquet DECIMAL, which holds no non-finite
     * value, so every artifact writes that key cell NULL — a quoted {@code 'NaN'} in the WHERE
     * clause would then be valid SQL addressing a baseline row whose key is NULL: applied, matching
     * nothing, mirror silently diverged. Sending a {@code numeric} column as {@code double_value}
     * violates the wire contract and nothing rejects it at ingest, so the skip is the guard.
     *
     * <p>A column declared {@code double precision} is the control: same wire case, same value,
     * representable at the destination, therefore rendered.</p>
     */
    @Test
    @DisplayName("should skip a non-finite double key only when its column materialises as a decimal")
    void shouldSkipNonFiniteDoubleKeyOnlyInDecimalColumn() throws IOException {
        Map<String, TableSchema> typed = Map.of(
                "priced", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "numeric(10,2)", true),
                                new TableSchema.ColumnDefinition("label", "varchar", true)),
                        List.of("id"), List.of()),
                "measured", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "double precision", true),
                                new TableSchema.ColumnDefinition("label", "varchar", true)),
                        List.of("id"), List.of()));

        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("priced", Op.DELETE, 1, Map.of("id", dbl(Double.NaN)), Map.of()),
                // the same hazard one wire case over: a string spelling, quoted just as legally
                record("priced", Op.DELETE, 3, Map.of("id", str("Infinity")), Map.of()),
                record("measured", Op.DELETE, 2, Map.of("id", dbl(Double.NaN)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 2, "DELTA")),
                typed, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.sqlContent())
                .doesNotContain("DELETE FROM priced")
                .contains("DELETE FROM measured WHERE id = 'NaN'");
        assertThat(result.stats().deletes()).isEqualTo(1);
    }

    /**
     * A <em>bare</em> {@code numeric} is Avro STRING, not DECIMAL — it carries the token losslessly —
     * so it is not the skipping case, and a guard written against the type <em>name</em> rather than
     * against the field the writers build would get this wrong.
     */
    @Test
    @DisplayName("should render a non-finite double key in a bare numeric column")
    void shouldRenderNonFiniteDoubleKeyInBareNumericColumn() throws IOException {
        Map<String, TableSchema> typed = Map.of(
                "priced", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "numeric", true)),
                        List.of("id"), List.of()));

        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("priced", Op.DELETE, 1, Map.of("id", dbl(Double.POSITIVE_INFINITY)), Map.of())));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 1, "DELTA")),
                typed, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.sqlContent()).contains("DELETE FROM priced WHERE id = 'Infinity'");
    }

    /**
     * Issue #240's second fork: Parquet DECIMAL's NULL is the contract, so SQL follows it for
     * <em>data</em> cells too. A {@code numeric(p,s)} column whose value arrives as
     * {@code double_value} / a non-finite {@code string_value} is NULL in every Parquet artifact
     * ({@code toBigDecimal} cannot render a non-finite into a DECIMAL whatever Java type it arrived
     * as). Until this, the SQL stream rendered {@code 'NaN'} for those cells — valid PostgreSQL that
     * stored a value the baseline does not have. Keys of that combination are already skipped
     * (issue #233); the data half is this ticket.
     *
     * <p>Controls: a {@code double precision} data cell is still quoted ({@link
     * #shouldRenderNonFiniteDoubleAsQuotedLiteral}), and a <em>bare</em> {@code numeric} is Avro
     * STRING so it is not a DECIMAL destination either.</p>
     */
    @Test
    @DisplayName("should render a non-finite data cell as NULL when its column is a Parquet decimal")
    void shouldNullNonFiniteDataCellInADecimalColumn() throws IOException {
        Map<String, TableSchema> typed = Map.of(
                "priced", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                                new TableSchema.ColumnDefinition("price", "numeric(10,2)", true),
                                new TableSchema.ColumnDefinition("note", "varchar", true)),
                        List.of("id"), List.of()),
                "measured", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                                new TableSchema.ColumnDefinition("reading", "double precision", true)),
                        List.of("id"), List.of()),
                "tokened", new TableSchema(
                        List.of(new TableSchema.ColumnDefinition("id", "bigint", false),
                                new TableSchema.ColumnDefinition("amount", "numeric", true)),
                        List.of("id"), List.of()));

        when(segmentService.readRecords(anyString())).thenReturn(List.of(
                record("priced", Op.UPDATE, 1, Map.of("id", intVal(1)),
                        Map.of("price", dbl(Double.NaN))),
                record("priced", Op.UPDATE, 2, Map.of("id", intVal(2)),
                        Map.of("price", str("Infinity"), "note", str("kept"))),
                record("priced", Op.INSERT, 3, Map.of("id", intVal(3)),
                        Map.of("price", dbl(Double.NEGATIVE_INFINITY))),
                record("measured", Op.UPDATE, 4, Map.of("id", intVal(4)),
                        Map.of("reading", dbl(Double.NaN))),
                record("tokened", Op.UPDATE, 5, Map.of("id", intVal(5)),
                        Map.of("amount", dbl(Double.NaN)))));

        SqlGenerationResult result = strategy.generate(BATCH, SITE, List.of(segment(1, 5, "DELTA")),
                typed, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.sqlContent())
                .contains("UPDATE priced SET price = NULL WHERE id = 1")
                .contains("UPDATE priced SET")
                .contains("price = NULL")
                .contains("note = 'kept'")
                .contains("VALUES (3, NULL)")
                .contains("UPDATE measured SET reading = 'NaN' WHERE id = 4")
                .contains("UPDATE tokened SET amount = 'NaN' WHERE id = 5")
                .doesNotContain("SET price = 'NaN'")
                .doesNotContain("VALUES (3, '-Infinity')");
        assertThat(result.stats().updates()).isEqualTo(4);
        assertThat(result.stats().inserts()).isEqualTo(1);
    }
}
