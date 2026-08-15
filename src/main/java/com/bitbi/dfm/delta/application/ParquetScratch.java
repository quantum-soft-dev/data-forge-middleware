package com.bitbi.dfm.delta.application;

/**
 * On-disk prefixes of the file-backed Parquet scratch files.
 *
 * <p>Writers and {@link ParquetScratchOrphanSweeper} must agree. A silent drift would
 * either leave dead files forever or miss live ones.</p>
 */
final class ParquetScratch {

    static final String CHECKPOINT_PREFIX = "checkpoint-";
    static final String BATCH_PARQUET_PREFIX = "batch-parquet-";

    private ParquetScratch() {
    }

    static boolean matches(String fileName) {
        return fileName.startsWith(CHECKPOINT_PREFIX) || fileName.startsWith(BATCH_PARQUET_PREFIX);
    }
}
