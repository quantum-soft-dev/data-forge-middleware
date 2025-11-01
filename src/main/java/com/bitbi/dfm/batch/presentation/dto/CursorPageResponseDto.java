package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Generic cursor-based pagination wrapper for API responses.
 * <p>
 * Provides cursor-based navigation for large datasets.
 * Cursor format: "{sortKey}_{tieBreaker}" (e.g., "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000")
 * </p>
 *
 * @param <T> Type of items in the page
 * @param items      Current page items
 * @param nextCursor Cursor for next page (null if last page)
 * @param hasNext    Convenience flag for UI (true if more pages exist)
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchHistoryController
 */
@Schema(description = "Cursor-based pagination response")
public record CursorPageResponseDto<T>(
        @Schema(description = "Current page items")
        List<T> items,

        @Schema(description = "Cursor for next page (null if last page)", example = "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000")
        String nextCursor,

        @Schema(description = "True if more pages exist", example = "true")
        boolean hasNext
) {
    /**
     * Create empty page response.
     *
     * @param <T> Item type
     * @return Empty page
     */
    public static <T> CursorPageResponseDto<T> empty() {
        return new CursorPageResponseDto<>(List.of(), null, false);
    }

    /**
     * Create page with items and cursor.
     *
     * @param items      Page items
     * @param nextCursor Cursor for next page (null if last page)
     * @param hasNext    True if more pages exist
     * @param <T>        Item type
     * @return Cursor page
     */
    public static <T> CursorPageResponseDto<T> of(List<T> items, String nextCursor, boolean hasNext) {
        return new CursorPageResponseDto<>(items, nextCursor, hasNext);
    }
}
