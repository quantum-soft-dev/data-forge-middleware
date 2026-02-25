package com.bitbi.dfm.site.domain;

import java.util.List;
import java.util.Optional;

/**
 * Value object representing the schema of a single database table.
 *
 * <p>Parsed from the JSONB schema_data stored in site_schemas table.</p>
 *
 * @param columns    ordered list of column definitions
 * @param primaryKey list of column names forming the primary key
 * @param uniqueKeys list of unique key definitions
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record TableSchema(
        List<ColumnDefinition> columns,
        List<String> primaryKey,
        List<UniqueKeyDefinition> uniqueKeys
) {

    /**
     * Definition of a single table column.
     *
     * @param name     column name
     * @param type     PostgreSQL column type (e.g., "integer", "varchar(255)")
     * @param nullable whether the column accepts NULL values
     */
    public record ColumnDefinition(String name, String type, boolean nullable) {
    }

    /**
     * Definition of a unique key constraint.
     *
     * @param name    constraint name
     * @param columns list of column names forming the unique key
     */
    public record UniqueKeyDefinition(String name, List<String> columns) {
    }

    /**
     * Find a column definition by name.
     *
     * @param columnName the column name to look up
     * @return optional column definition
     */
    public Optional<ColumnDefinition> findColumn(String columnName) {
        return columns.stream()
                .filter(c -> c.name().equals(columnName))
                .findFirst();
    }

    /**
     * Check whether a column exists in this table schema.
     *
     * @param columnName the column name to check
     * @return true if the column exists
     */
    public boolean hasColumn(String columnName) {
        return columns.stream().anyMatch(c -> c.name().equals(columnName));
    }
}
