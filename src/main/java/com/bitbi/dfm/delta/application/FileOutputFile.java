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
 * <p>The same bytes are charged to the {@link ScratchLease} of the shared scratch directory
 * (issue #150). A per-file ceiling cannot bound a directory whose file <em>count</em> is set by the
 * batch's table count, so both checks run on every write and either can stop the writer — the
 * difference being what it means: crossing the ceiling is a verdict on the artifact, crossing the
 * budget is a fact about the volume at that moment.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class FileOutputFile implements OutputFile {

    private final Path path;
    private final long maxBytes;
    private final ScratchLease lease;

    FileOutputFile(Path path, long maxBytes, ScratchLease lease) {
        this.path = path;
        this.maxBytes = maxBytes;
        this.lease = lease;
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

            private void checkCapacity(int length) throws IOException {
                if (length > maxBytes - position) {
                    // Parquet unwinds a write failure through close() paths that never close the
                    // output themselves, so nothing else would release this descriptor — and on the
                    // checkpoint path the build survives the ceiling and runs again every night.
                    output.close();
                    throw new ArtifactSizeLimitExceededException(maxBytes);
                }
                try {
                    lease.charge(length);
                } catch (ScratchBudgetExceededException e) {
                    // Same descriptor argument as above: the budget refusal unwinds through the
                    // same Parquet close() paths.
                    output.close();
                    throw e;
                }
            }
        };
    }
}
