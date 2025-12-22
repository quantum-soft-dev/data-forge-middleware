package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for SQL changes endpoint.
 * Note: The actual response is plain text (text/plain), but this DTO
 * documents the expected response format.
 *
 * @param sqlContent Concatenated SQL statements from all matching files
 */
@Schema(description = "SQL changes response (returned as text/plain)")
public record SqlChangesResponseDto(
    @Schema(description = "Concatenated SQL statements", example = """
        INSERT INTO customers (id, name) VALUES ('1', 'John');
        --- END OF COMMAND "customers.csv:2" ---
        UPDATE orders SET status = 'shipped' WHERE id = '42';
        --- END OF COMMAND "orders.csv:15" ---
        """)
    String sqlContent
) {
    /**
     * Creates an empty response (no changes found).
     */
    public static SqlChangesResponseDto empty() {
        return new SqlChangesResponseDto("");
    }

    /**
     * Creates a response from SQL content.
     */
    public static SqlChangesResponseDto of(String content) {
        return new SqlChangesResponseDto(content != null ? content : "");
    }

    /**
     * Returns true if there are no SQL changes.
     */
    public boolean isEmpty() {
        return sqlContent == null || sqlContent.isEmpty();
    }
}
