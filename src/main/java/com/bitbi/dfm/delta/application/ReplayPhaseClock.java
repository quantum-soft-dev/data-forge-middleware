package com.bitbi.dfm.delta.application;

/**
 * Thread-bound accumulator for streaming changelog replay: download is GetObject / stream
 * {@code read}, decode is parse time excluding the record consumer.
 */
final class ReplayPhaseClock {

    private static final ThreadLocal<ReplayPhaseClock> CURRENT = new ThreadLocal<>();

    private long downloadNanos;
    private long decodeNanos;
    private boolean sampled;

    static Scope bind() {
        ReplayPhaseClock clock = new ReplayPhaseClock();
        CURRENT.set(clock);
        return new Scope(clock);
    }

    static ReplayPhaseClock current() {
        return CURRENT.get();
    }

    void addDownload(long nanos) {
        downloadNanos += Math.max(0L, nanos);
        sampled = true;
    }

    void addDecode(long nanos) {
        decodeNanos += Math.max(0L, nanos);
        sampled = true;
    }

    long downloadNanos() {
        return downloadNanos;
    }

    long decodeNanos() {
        return decodeNanos;
    }

    boolean hasSamples() {
        return sampled;
    }

    static final class Scope implements AutoCloseable {
        private final ReplayPhaseClock clock;

        private Scope(ReplayPhaseClock clock) {
            this.clock = clock;
        }

        ReplayPhaseClock clock() {
            return clock;
        }

        @Override
        public void close() {
            CURRENT.remove();
        }
    }
}
