package com.bitbi.dfm.plugin.application;

import java.io.IOException;

/**
 * Strategy for generating SQL content from a completed batch.
 *
 * <p>Known implementations:</p>
 * <ul>
 *   <li>{@link DbfSqlGenerationStrategy} — DBF sites: compares consecutive CSV snapshots via Myers diff</li>
 *   <li>{@link CdcSqlGenerationStrategy} — Postgres CDC sites: converts JSONL deltas directly to SQL</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SqlGenerationStrategy {

    /**
     * Generates SQL content for the given batch context.
     *
     * @param context batch context (files, schemas, IDs)
     * @return result containing SQL text and change statistics,
     *         or {@code null} if no changes were detected
     * @throws IOException if reading from S3 fails
     */
    SqlGenerationResult generate(SqlGenerationContext context) throws IOException;
}
