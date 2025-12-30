package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for listing tables in the Plugin API.
 *
 * @param tables List of tables (unique file names) belonging to the account
 */
@Schema(description = "List of tables response")
public record TableListResponseDto(
    @Schema(description = "List of tables belonging to the account")
    List<TableDto> tables
) {
    /**
     * Creates a response with the given tables.
     */
    public static TableListResponseDto of(List<TableDto> tables) {
        return new TableListResponseDto(tables != null ? List.copyOf(tables) : List.of());
    }

    /**
     * Creates an empty response (no tables).
     */
    public static TableListResponseDto empty() {
        return new TableListResponseDto(List.of());
    }

    /**
     * Returns true if there are no tables.
     */
    public boolean isEmpty() {
        return tables == null || tables.isEmpty();
    }

    /**
     * Returns the number of tables.
     */
    public int size() {
        return tables != null ? tables.size() : 0;
    }
}
