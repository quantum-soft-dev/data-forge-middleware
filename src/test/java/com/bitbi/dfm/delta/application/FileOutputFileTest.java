package com.bitbi.dfm.delta.application;

import org.apache.parquet.io.PositionOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The byte ceiling of the shared file-backed Parquet output (issue #112). It is checked
 * <em>during</em> output, and hitting it must not strand the open file descriptor: on the checkpoint
 * path a table over the ceiling is skipped and the build keeps going, so a leak there would cost one
 * descriptor per nightly build until the pod runs out.
 */
class FileOutputFileTest {

    @TempDir
    Path tempDir;

    @Test
    void writesThroughToTheFileWhileWithinTheCeiling() throws IOException {
        Path file = tempDir.resolve("within.parquet");
        try (PositionOutputStream stream = new FileOutputFile(file, 16, TestScratchLeases.unbounded()).create(0L)) {
            stream.write(new byte[]{1, 2, 3}, 0, 3);
            stream.write(4);
            assertEquals(4L, stream.getPos());
        }
        assertEquals(4L, Files.size(file));
    }

    @Test
    void closesTheFileWhenTheCeilingIsCrossed() throws IOException {
        Path file = tempDir.resolve("over.parquet");
        PositionOutputStream stream = new FileOutputFile(file, 4, TestScratchLeases.unbounded()).create(0L);

        assertThrows(ArtifactSizeLimitExceededException.class,
                () -> stream.write(new byte[]{1, 2, 3, 4, 5}, 0, 5));

        // Parquet unwinds through close() paths that do not close the output themselves, so the
        // stream has to close itself here or the descriptor is never released.
        assertThrows(IOException.class, () -> stream.write(1),
                "the underlying file must already be closed");
    }
}
