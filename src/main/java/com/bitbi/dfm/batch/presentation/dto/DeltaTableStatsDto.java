package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.delta.domain.TableChangeStats;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Per-table insert/update/delete counts for one Delta v2 session, surfaced in batch history
 * in place of the always-zero file count (Delta writes no {@code uploaded_files}).
 *
 * @param table    table name
 * @param inserts  rows inserted
 * @param updates  rows updated
 * @param deletes  rows deleted
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Per-table insert/update/delete counts for a Delta v2 session")
public record DeltaTableStatsDto(
        @Schema(description = "Table name", example = "orders") String table,
        @Schema(description = "Rows inserted", example = "12") long inserts,
        @Schema(description = "Rows updated", example = "5") long updates,
        @Schema(description = "Rows deleted", example = "1") long deletes
) {
    public static DeltaTableStatsDto of(String table, TableChangeStats stats) {
        return new DeltaTableStatsDto(table, stats.inserts(), stats.updates(), stats.deletes());
    }
}
