package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangelogCodecTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsGzippedDelimitedRecordsInWireOrder() {
        List<ChangeRecord> source = List.of(record(7), record(8), record(12));
        List<Long> sequences = new ArrayList<>();

        ChangelogCodec.forEach(new ByteArrayInputStream(ChangelogCodec.serialize(source)),
                record -> sequences.add(record.getSeq()));

        assertEquals(List.of(7L, 8L, 12L), sequences);
    }

    @Test
    void writeThenParseRoundTripsTheSameRecordsAsSerialize() {
        List<ChangeRecord> source = List.of(record(7), record(8), record(12));

        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        ChangelogCodec.write(source, streamed);

        assertEquals(ChangelogCodec.parse(ChangelogCodec.serialize(source)),
                ChangelogCodec.parse(streamed.toByteArray()),
                "a frame written to a stream must be readable by the existing parse");
    }

    @Test
    void parseReadsAFrameWrittenToAScratchFile() throws IOException {
        List<ChangeRecord> source = List.of(record(1), record(2));
        Path file = tempDir.resolve("frame.pb.gz");

        ChangelogCodec.write(source, file, Long.MAX_VALUE);

        assertEquals(source, ChangelogCodec.parse(Files.readAllBytes(file)));
    }

    @Test
    void writeStopsWhenTheScratchFileWouldCrossTheCeiling() {
        List<ChangeRecord> source = List.of(record(1), record(2), record(3));
        Path file = tempDir.resolve("frame.pb.gz");

        assertThrows(ArtifactSizeLimitExceededException.class,
                () -> ChangelogCodec.write(source, file, 8L));
    }

    private static ChangeRecord record(long seq) {
        return ChangeRecord.newBuilder().setTable("orders").setOp(Op.INSERT).setSeq(seq).build();
    }
}
