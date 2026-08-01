package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangelogCodecTest {

    @Test
    void streamsGzippedDelimitedRecordsInWireOrder() {
        List<ChangeRecord> source = List.of(record(7), record(8), record(12));
        List<Long> sequences = new ArrayList<>();

        ChangelogCodec.forEach(new ByteArrayInputStream(ChangelogCodec.serialize(source)),
                record -> sequences.add(record.getSeq()));

        assertEquals(List.of(7L, 8L, 12L), sequences);
    }

    private static ChangeRecord record(long seq) {
        return ChangeRecord.newBuilder().setTable("orders").setOp(Op.INSERT).setSeq(seq).build();
    }
}
