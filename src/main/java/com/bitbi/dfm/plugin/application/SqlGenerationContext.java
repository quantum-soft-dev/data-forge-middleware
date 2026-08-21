package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.domain.UploadedFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Context provided to a {@link SqlGenerationStrategy} for a single batch invocation.
 * <p>
 * Package-private: consumed only within the {@code plugin.application} package.
 * </p>
 *
 * @param batchId          the batch being processed
 * @param siteId           the owning site
 * @param relevantFiles    files to process — CSV/CSV.GZ for DBF, JSONL/JSONL.GZ for CDC
 * @param previousFilesMap previous-batch files keyed by normalised name (DBF only; empty for CDC)
 * @param tableSchemas     parsed table schemas keyed by table name (CDC filters columns
 *                         against it; DBF maps PostgreSQL types onto {@code DbfColumnType})
 */
record SqlGenerationContext(
        UUID batchId,
        UUID siteId,
        List<UploadedFile> relevantFiles,
        Map<String, UploadedFile> previousFilesMap,
        Map<String, TableSchema> tableSchemas
) {}
