package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shared disk ceiling for file-backed checkpoint artifacts (issue #126). Further writes after
 * the first exceed must not throw — gzip's close path writes a trailer, and a second exception
 * would hide the {@link ArtifactSizeLimitExceededException} the caller is meant to see.
 */
class CappedOutputStreamTest {

    @Test
    void writesUntilTheCeilingThenThrows() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CappedOutputStream capped = new CappedOutputStream(sink, 4, TestScratchLeases.unbounded());

        capped.write(new byte[] {1, 2, 3});
        assertThrows(ArtifactSizeLimitExceededException.class, () -> capped.write(new byte[] {4, 5}));
        assertArrayEquals(new byte[] {1, 2, 3}, sink.toByteArray());
    }

    @Test
    void furtherWritesAfterTheCeilingDoNotThrow() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CappedOutputStream capped = new CappedOutputStream(sink, 0, TestScratchLeases.unbounded());

        assertThrows(ArtifactSizeLimitExceededException.class, () -> capped.write(1));
        capped.write(2);
        capped.write(new byte[] {3, 4});
        assertArrayEquals(new byte[] {}, sink.toByteArray());
    }
}
