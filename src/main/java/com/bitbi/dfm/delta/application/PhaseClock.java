package com.bitbi.dfm.delta.application;

/**
 * Accumulator for one completed-batch Parquet cycle. Download/decode are filled by
 * streaming replay; decimal-scan/write are filled by the writer. The caller maps
 * the totals onto meter tags.
 */
final class PhaseClock {

    private long downloadNanos;
    private long decodeNanos;
    private long decimalScanNanos;
    private long writeNanos;
    private boolean sampled;
    private boolean decimalScanAttempted;
    private boolean writeAttempted;

    void addDownload(long nanos) {
        downloadNanos += Math.max(0L, nanos);
        sampled = true;
    }

    void addDecode(long nanos) {
        decodeNanos += Math.max(0L, nanos);
        sampled = true;
    }

    void addDecimalScan(long nanos) {
        decimalScanNanos += Math.max(0L, nanos);
        decimalScanAttempted = true;
        sampled = true;
    }

    void addWrite(long nanos) {
        writeNanos += Math.max(0L, nanos);
        writeAttempted = true;
        sampled = true;
    }

    long downloadNanos() {
        return downloadNanos;
    }

    long decodeNanos() {
        return decodeNanos;
    }

    long decimalScanNanos() {
        return decimalScanNanos;
    }

    long writeNanos() {
        return writeNanos;
    }

    boolean hasSamples() {
        return sampled;
    }

    boolean decimalScanAttempted() {
        return decimalScanAttempted;
    }

    boolean writeAttempted() {
        return writeAttempted;
    }
}
