package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Service for generating PostgreSQL SQL statements from CSV row diffs.
 * Produces INSERT, UPDATE, DELETE statements with proper formatting.
 */
@Service
public class SqlStatementGenerator {

    private static final String END_OF_COMMAND_FORMAT = "--- END OF COMMAND \"%s.csv:%d\" ---";

    /**
     * Generates a SQL statement from a row diff.
     *
     * @param diff The row difference (ADDED, MODIFIED, DELETED)
     * @param tableName The table name (derived from CSV filename)
     * @param columnTypes Map of column name to DBF type for NULL/value handling
     * @return The SQL statement with trailing END OF COMMAND comment
     */
    public String generate(CsvRowDiff diff, String tableName, Map<String, DbfColumnType> columnTypes) {
        String sql = switch (diff.type()) {
            case ADDED -> generateInsert(diff, tableName, columnTypes);
            case MODIFIED -> generateUpdate(diff, tableName, columnTypes);
            case DELETED -> generateDelete(diff, tableName, columnTypes);
        };

        return sql + ";\n" + String.format(END_OF_COMMAND_FORMAT, tableName, diff.lineNumber()) + "\n";
    }

    /**
     * Generates an INSERT statement.
     */
    private String generateInsert(CsvRowDiff diff, String tableName, Map<String, DbfColumnType> columnTypes) {
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" ");

        StringJoiner columns = new StringJoiner(", ", "(", ")");
        StringJoiner values = new StringJoiner(", ", "(", ")");

        for (Map.Entry<String, String> entry : diff.values().entrySet()) {
            String column = entry.getKey();
            String value = entry.getValue();
            DbfColumnType type = columnTypes.getOrDefault(column, DbfColumnType.CHARACTER);

            columns.add(column);
            values.add(formatValue(value, type));
        }

        sql.append(columns);
        sql.append(" VALUES ");
        sql.append(values);

        return sql.toString();
    }

    /**
     * Generates an UPDATE statement.
     * Uses unchanged columns in WHERE clause, changed columns in SET clause.
     */
    private String generateUpdate(CsvRowDiff diff, String tableName, Map<String, DbfColumnType> columnTypes) {
        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableName).append(" SET ");

        // SET clause - only changed columns
        StringJoiner setClause = new StringJoiner(", ");
        for (String changedColumn : diff.changedColumns().keySet()) {
            String newValue = diff.values().get(changedColumn);
            DbfColumnType type = columnTypes.getOrDefault(changedColumn, DbfColumnType.CHARACTER);
            setClause.add(changedColumn + " = " + formatValue(newValue, type));
        }
        sql.append(setClause);

        // WHERE clause - unchanged columns
        sql.append(" WHERE ");
        StringJoiner whereClause = new StringJoiner(" AND ");
        for (Map.Entry<String, String> entry : diff.values().entrySet()) {
            String column = entry.getKey();
            if (!diff.changedColumns().containsKey(column)) {
                String value = entry.getValue();
                DbfColumnType type = columnTypes.getOrDefault(column, DbfColumnType.CHARACTER);
                whereClause.add(column + " = " + formatValue(value, type));
            }
        }
        sql.append(whereClause);

        return sql.toString();
    }

    /**
     * Generates a DELETE statement.
     * Uses all columns in WHERE clause.
     */
    private String generateDelete(CsvRowDiff diff, String tableName, Map<String, DbfColumnType> columnTypes) {
        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(tableName).append(" WHERE ");

        StringJoiner whereClause = new StringJoiner(" AND ");
        for (Map.Entry<String, String> entry : diff.values().entrySet()) {
            String column = entry.getKey();
            String value = entry.getValue();
            DbfColumnType type = columnTypes.getOrDefault(column, DbfColumnType.CHARACTER);
            whereClause.add(column + " = " + formatValue(value, type));
        }
        sql.append(whereClause);

        return sql.toString();
    }

    /**
     * Formats a value for SQL based on its DBF type.
     * Handles NULL for empty values, quoting for strings, no quotes for numbers.
     */
    private String formatValue(String value, DbfColumnType type) {
        // Handle empty values
        if (value == null || value.isEmpty()) {
            if (type.isEmptyNull()) {
                return "NULL";
            } else {
                return "0"; // INTEGER and CURRENCY types
            }
        }

        // Numeric types - no quotes
        if (isNumericType(type)) {
            return value;
        }

        // String types - escape and quote
        return "'" + escapeString(value) + "'";
    }

    /**
     * Checks if a type is numeric (no quoting needed).
     */
    private boolean isNumericType(DbfColumnType type) {
        return type == DbfColumnType.INTEGER ||
               type == DbfColumnType.CURRENCY ||
               type == DbfColumnType.NUMERIC ||
               type == DbfColumnType.FLOAT;
    }

    /**
     * Escapes a string value for PostgreSQL.
     * Doubles single quotes as per SQL standard.
     */
    private String escapeString(String value) {
        return value.replace("'", "''");
    }
}
