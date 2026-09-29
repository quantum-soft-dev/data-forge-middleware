package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.site.domain.TableSchema;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #369 — a record carrying {@code row_hash} reaches the Bit BI SQL path and changes nothing
 * there: the statements address rows by {@code key}, exactly as for a record without one, and the
 * hash is never rendered. Moving the SQL onto the hash needs DDL in the Bit BI mirror tables and is
 * out of this ticket's scope.
 */
class DeltaSqlGenerationRowHashTest {

    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID SITE = UUID.randomUUID();
    private static final ByteString HASH = ByteString.copyFrom(new byte[] {
            (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28});

    private final Map<String, TableSchema> schemas = Map.of("customers", new TableSchema(List.of(
            new TableSchema.ColumnDefinition("id", "varchar", true),
            new TableSchema.ColumnDefinition("name", "varchar", true)), List.of("id"), List.of()));

    @Test
    void aRowHashChangesNeitherTheStatementsNorTheirCounts() throws Exception {
        SqlGenerationResult bare = generate(records(ByteString.EMPTY));
        SqlGenerationResult hashed = generate(records(HASH));

        assertThat(hashed.sqlContent()).isEqualTo(bare.sqlContent());
        assertThat(hashed.stats().inserts()).isEqualTo(1);
        assertThat(hashed.stats().deletes()).isEqualTo(1);
        assertThat(hashed.sqlContent())
                .doesNotContain("row_hash")
                .doesNotContain(HexFormat.of().formatHex(HASH.toByteArray()));
    }

    private SqlGenerationResult generate(List<ChangeRecord> records) throws Exception {
        ChangelogSegmentService segments = mock(ChangelogSegmentService.class);
        // Through the segment codec, as the queue reads it: the field has to survive the round trip
        // and still be ignored at the end of it.
        when(segments.readRecords(anyString())).thenReturn(ChangelogCodec.parse(ChangelogCodec.serialize(records)));
        DeltaSqlGenerationStrategy strategy =
                new DeltaSqlGenerationStrategy(segments, new SqlStatementGenerator(), new SimpleMeterRegistry());
        ChangelogSegment segment = ChangelogSegment.create(SITE, BATCH, 1L, 2L, 2L, "hash", "delta/key/1",
                "DELTA", Map.of());
        return strategy.generate(BATCH, SITE, List.of(segment), schemas, Map.of());
    }

    private static List<ChangeRecord> records(ByteString rowHash) {
        Map<String, Value> key = Map.of("id", Value.newBuilder().setStringValue("7").build());
        return List.of(
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.INSERT).setSeq(1).putAllKey(key)
                        .putData("name", Value.newBuilder().setStringValue("Ann").build())
                        .setRowHash(rowHash).build(),
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.DELETE).setSeq(2).putAllKey(key)
                        .setRowHash(rowHash).build());
    }
}
