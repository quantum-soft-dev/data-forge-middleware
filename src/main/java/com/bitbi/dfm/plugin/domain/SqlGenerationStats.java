package com.bitbi.dfm.plugin.domain;

/**
 * Value object for tracking SQL generation statistics.
 * Immutable record for aggregating INSERT, UPDATE, DELETE counts.
 */
public record SqlGenerationStats(
    int inserts,
    int updates,
    int deletes,
    int filesProcessed
) {
    /**
     * Returns total statement count (inserts + updates + deletes).
     */
    public int total() {
        return inserts + updates + deletes;
    }

    /**
     * Creates an empty stats object with all zeros.
     */
    public static SqlGenerationStats empty() {
        return new SqlGenerationStats(0, 0, 0, 0);
    }

    /**
     * Merges this stats with another, summing all counts.
     */
    public SqlGenerationStats merge(SqlGenerationStats other) {
        return new SqlGenerationStats(
            this.inserts + other.inserts,
            this.updates + other.updates,
            this.deletes + other.deletes,
            this.filesProcessed + other.filesProcessed
        );
    }

    /**
     * Creates stats for a single file's results.
     */
    public static SqlGenerationStats forFile(int inserts, int updates, int deletes) {
        return new SqlGenerationStats(inserts, updates, deletes, 1);
    }
}
