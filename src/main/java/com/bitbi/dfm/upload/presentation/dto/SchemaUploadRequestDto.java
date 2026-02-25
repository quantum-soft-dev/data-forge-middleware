package com.bitbi.dfm.upload.presentation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for POST /api/dfc/schema — table schema upload.
 *
 * <p>The {@code tables} map contains one entry per table. Each entry maps
 * the table name to its {@link TableSchemaDto}.</p>
 *
 * @param tables map of table name → table schema definition
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Table schema definition for a site")
public record SchemaUploadRequestDto(

        @Schema(description = "Map of table name to table schema definition", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "tables map is required")
        Map<String, TableSchemaDto> tables

) {

    /**
     * Schema definition for a single table.
     *
     * @param columns    ordered list of column definitions
     * @param primaryKey list of column names forming the primary key
     * @param uniqueKeys list of unique key definitions (may be empty)
     */
    @Schema(description = "Schema definition for a single database table")
    public record TableSchemaDto(

            @Schema(description = "Ordered list of column definitions", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "columns list is required")
            List<ColumnDto> columns,

            @Schema(description = "Column names forming the primary key", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "primaryKey list is required")
            List<String> primaryKey,

            @Schema(description = "Unique key constraint definitions")
            List<UniqueKeyDto> uniqueKeys

    ) {
    }

    /**
     * Column definition.
     *
     * @param name     column name (PostgreSQL identifier)
     * @param type     PostgreSQL type string (e.g., "integer", "varchar(255)")
     * @param nullable whether the column is nullable
     */
    @Schema(description = "Column definition")
    public record ColumnDto(

            @Schema(description = "Column name", example = "customer_id", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "column name is required")
            String name,

            @Schema(description = "PostgreSQL column type", example = "integer", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "column type is required")
            String type,

            @Schema(description = "Whether this column is nullable", example = "false")
            boolean nullable

    ) {
    }

    /**
     * Unique key definition.
     *
     * @param name    constraint name
     * @param columns list of column names forming the unique key
     */
    @Schema(description = "Unique key constraint")
    public record UniqueKeyDto(

            @Schema(description = "Constraint name", example = "uk_customers_email")
            String name,

            @Schema(description = "Column names forming the unique key")
            List<String> columns

    ) {
    }
}
