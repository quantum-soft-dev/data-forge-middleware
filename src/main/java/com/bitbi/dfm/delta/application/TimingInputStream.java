package com.bitbi.dfm.delta.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts nanos spent in {@code read} so a streaming replay can attribute S3 transfer. */
final class TimingInputStream extends FilterInputStream {

    private long readNanos;

    TimingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        long started = System.nanoTime();
        try {
            return in.read();
        } finally {
            readNanos += System.nanoTime() - started;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        long started = System.nanoTime();
        try {
            return in.read(buffer, offset, length);
        } finally {
            readNanos += System.nanoTime() - started;
        }
    }

    long readNanos() {
        return readNanos;
    }
}
