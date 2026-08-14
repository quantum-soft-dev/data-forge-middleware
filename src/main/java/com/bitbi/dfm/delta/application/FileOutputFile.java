package com.bitbi.dfm.delta.application;

import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal Hadoop-free Parquet output backed by a local file, bounded by a byte ceiling.
 *
 * <p>Shared by the completed-batch writer (036–038) and, since issue #112, the checkpoint snapshot
 * writer: both stream their artifact to disk instead of building it in heap. The ceiling is checked
 * <em>during</em> output rather than on the finished file, so an oversized table stops at the limit
 * instead of filling the node first.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class FileOutputFile implements OutputFile {

    private final Path path;
    private final long maxBytes;

    FileOutputFile(Path path, long maxBytes) {
        this.path = path;
        this.maxBytes = maxBytes;
    }

    @Override
    public PositionOutputStream create(long blockSizeHint) throws IOException {
        return stream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    @Override
    public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
        return stream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    @Override
    public boolean supportsBlockSize() {
        return false;
    }

    @Override
    public long defaultBlockSize() {
        return 0L;
    }

    private PositionOutputStream stream(OpenOption... options) throws IOException {
        OutputStream output = Files.newOutputStream(path, options);
        return new PositionOutputStream() {
            private long position;

            @Override
            public long getPos() {
                return position;
            }

            @Override
            public void write(int value) throws IOException {
                checkCapacity(1);
                output.write(value);
                position++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                checkCapacity(length);
                output.write(bytes, offset, length);
                position += length;
            }

            @Override
            public void flush() throws IOException {
                output.flush();
            }

            @Override
            public void close() throws IOException {
                output.close();
            }

            private void checkCapacity(int length) {
                if (length > maxBytes - position) {
                    throw new ArtifactSizeLimitExceededException(maxBytes);
                }
            }
        };
    }
}
