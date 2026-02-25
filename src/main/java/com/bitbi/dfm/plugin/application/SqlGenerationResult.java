package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.SqlGenerationStats;

/**
 * Holds the result of a single {@link SqlGenerationStrategy} invocation.
 * <p>
 * Package-private: consumed only within the {@code plugin.application} package.
 * </p>
 *
 * @param sqlContent the generated SQL text
 * @param stats      insert / update / delete / files-processed counters
 */
record SqlGenerationResult(String sqlContent, SqlGenerationStats stats) {}
