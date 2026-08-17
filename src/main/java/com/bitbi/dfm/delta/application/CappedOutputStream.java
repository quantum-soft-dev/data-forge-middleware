package com.bitbi.dfm.delta.application;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Counts bytes as they hit disk and stops the write at {@code maxBytes}.
 *
 * <p>Shared by the checkpoint frame (issue #126) and available to any file-backed artifact
 * that is not a Parquet {@code OutputFile}. Once the ceiling is crossed, further writes are
 * dropped so a closer (gzip trailer) cannot raise a second, suppressed exception on top of
 * {@link ArtifactSizeLimitExceededException}.</p>
 *
 * <p>The same bytes are charged to the {@link ScratchLease} of the shared scratch directory
 * (issue #150), so this file is bounded twice: by its own ceiling and by what the other live
 * writers have already put on the volume. A {@link ScratchBudgetExceededException} is latched the
 * same way and for the same reason.</p>
 */
final class CappedOutputStream extends FilterOutputStream {

    private final long maxBytes;
    private final ScratchLease lease;
    private long written;
    private boolean exceeded;

    CappedOutputStream(OutputStream out, long maxBytes, ScratchLease lease) {
        super(out);
        this.maxBytes = maxBytes;
        this.lease = lease;
    }

    @Override
    public void write(int b) throws IOException {
        if (exceeded) {
            return;
        }
        checkCapacity(1);
        out.write(b);
        written++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (exceeded) {
            return;
        }
        checkCapacity(length);
        out.write(bytes, offset, length);
        written += length;
    }

    private void checkCapacity(int length) {
        if (length > maxBytes - written) {
            exceeded = true;
            throw new ArtifactSizeLimitExceededException(maxBytes);
        }
        try {
            lease.charge(length);
        } catch (ScratchBudgetExceededException e) {
            exceeded = true;
            throw e;
        }
    }
}
