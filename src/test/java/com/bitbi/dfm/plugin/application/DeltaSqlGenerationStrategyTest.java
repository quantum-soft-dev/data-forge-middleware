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
}
