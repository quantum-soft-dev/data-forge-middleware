package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Service for generating PostgreSQL SQL statements from CSV row diffs.
 * Produces INSERT, UPDATE, DELETE statements with proper formatting.
 * <p>
 * Security: Validates table/column names and escapes values to prevent SQL injection.
 * </p>
 */
@Service
public class SqlStatementGenerator {

    private static final String END_OF_COMMAND_FORMAT = "--- END OF COMMAND \"%s.csv:%d\" ---";

    /**
     * Pattern for valid PostgreSQL identifiers (table/column names).
     * Allows: letters, digits, underscores. Must start with letter or underscore.
     * Max 63 characters per PostgreSQL limit.
     */
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    /**
     * Generates a SQL statement from a row diff.
     *
     * @param diff The row difference (ADDED, MODIFIED, DELETED)
     * @param tableName The table name (derived from CSV filename)
     * @param columnTypes Map of column name to DBF type for NULL/value handling
     * @return The SQL statement with trailing END OF COMMAND comment
     * @throws IllegalArgumentException if table name or column names contain invalid characters
     */
    public String generate(CsvRowDiff diff, String tableName, Map<String, DbfColumnType> columnTypes) {
        // Security: Validate table name to prevent SQL injection
        validateIdentifier(tableName, "Table name");

        // Security: Validate all column names
        for (String column : diff.values().keySet()) {
            validateIdentifier(column, "Column name");
        }

        String sql = switch (diff.type()) {
            case ADDED -> generateInsert(diff, tableName, columnTypes);
            case MODIFIED -> generateUpdate(diff, tableName, columnTypes);
            case DELETED -> generateDelete(diff, tableName, columnTypes);
        };

        return sql + ";\n" + String.format(END_OF_COMMAND_FORMAT, tableName, diff.lineNumber()) + "\n";
    }

    /**
     * Validates that an identifier (table/column name) is safe for SQL.
     * Prevents SQL injection through malicious identifiers.
     *
     * @param identifier The identifier to validate
     * @param description Description for error message (e.g., "Table name", "Column name")
     * @throws IllegalArgumentException if identifier is invalid
     */
    private void validateIdentifier(String identifier, String description) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(description + " cannot be null or empty");
        }
        if (!VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    description + " contains invalid characters: '" + sanitizeForLogging(identifier) +
                    "'. Only letters, digits, and underscores are allowed, starting with letter or underscore.");
        }
    }

    /**
     * Sanitizes a string for safe logging (prevents log injection).
     */
    private String sanitizeForLogging(String input) {
        if (input == null) return "null";
        // Replace control characters and limit length
        return input.replaceAll("[\\p{Cntrl}]", "?").substring(0, Math.min(input.length(), 50));
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

    // --- CDC (JSONL) generation methods ---

    /**
     * Generates a SQL statement from a JSONL change record (CDC / {@code POSTGRES_CDC} mode).
     *
     * <p>Type-aware value formatting based on JSON type:</p>
     * <ul>
     *   <li>JSON {@code null} → SQL {@code NULL}</li>
     *   <li>JSON number → unquoted numeric literal</li>
     *   <li>JSON boolean → unquoted {@code true} / {@code false}</li>
     *   <li>JSON string → single-quoted, SQL-escaped string</li>
     * </ul>
     *
     * <p>The {@code k} field in the JSONL record provides the primary-key values used
     * for the WHERE clause of UPDATE and DELETE statements — no schema lookup is needed.</p>
     *
     * @param record    the change record parsed from JSONL
     * @param tableName the target table name (validated against identifier pattern)
     * @return SQL statement with trailing END OF COMMAND comment
     * @throws IllegalArgumentException if the table name or any column name is invalid
     */
    public String generateFromJsonl(JsonlChangeRecord record, String tableName) {
        validateIdentifier(tableName, "Table name");

        String sql = switch (record.op()) {
            case JsonlChangeRecord.OP_INSERT -> generateCdcInsert(record, tableName);
            case JsonlChangeRecord.OP_UPDATE -> generateCdcUpdate(record, tableName);
            case JsonlChangeRecord.OP_DELETE -> generateCdcDelete(record, tableName);
            default -> throw new IllegalArgumentException("Unknown JSONL op: " + record.op());
        };

        return sql + ";\n" + String.format(END_OF_COMMAND_FORMAT, tableName, record.lineNumber()) + "\n";
    }

    private String generateCdcInsert(JsonlChangeRecord record, String tableName) {
        if (record.data() == null || record.data().isEmpty()) {
            throw new IllegalArgumentException(
                    "INSERT record has no data at line " + record.lineNumber());
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" ");

        StringJoiner columns = new StringJoiner(", ", "(", ")");
        StringJoiner values = new StringJoiner(", ", "(", ")");

        for (Map.Entry<String, Object> entry : record.data().entrySet()) {
            String column = entry.getKey();
            validateIdentifier(column, "Column name");
            columns.add(column);
            values.add(formatJsonValue(entry.getValue()));
        }

        sql.append(columns).append(" VALUES ").append(values);
        return sql.toString();
    }

    private String generateCdcUpdate(JsonlChangeRecord record, String tableName) {
        if (record.key() == null || record.key().isEmpty()) {
            throw new IllegalArgumentException(
                    "UPDATE record missing key at line " + record.lineNumber());
        }

        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableName).append(" SET ");

        StringJoiner setClause = new StringJoiner(", ");
        if (record.data() != null) {
            for (Map.Entry<String, Object> entry : record.data().entrySet()) {
                String column = entry.getKey();
                validateIdentifier(column, "Column name");
                setClause.add(column + " = " + formatJsonValue(entry.getValue()));
            }
        }
        sql.append(setClause);

        sql.append(" WHERE ");
        StringJoiner whereClause = new StringJoiner(" AND ");
        for (Map.Entry<String, Object> entry : record.key().entrySet()) {
            String column = entry.getKey();
            validateIdentifier(column, "Column name");
            whereClause.add(column + " = " + formatJsonValue(entry.getValue()));
        }
        sql.append(whereClause);

        return sql.toString();
    }

    private String generateCdcDelete(JsonlChangeRecord record, String tableName) {
        if (record.key() == null || record.key().isEmpty()) {
            throw new IllegalArgumentException(
                    "DELETE record missing key at line " + record.lineNumber());
        }

        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(tableName).append(" WHERE ");

        StringJoiner whereClause = new StringJoiner(" AND ");
        for (Map.Entry<String, Object> entry : record.key().entrySet()) {
            String column = entry.getKey();
            validateIdentifier(column, "Column name");
            whereClause.add(column + " = " + formatJsonValue(entry.getValue()));
        }
        sql.append(whereClause);

        return sql.toString();
    }

    /**
     * Formats a typed value for use in a SQL statement.
     * Handles null, numbers (BigDecimal without scientific notation), booleans, bytea and strings.
     */
    private String formatJsonValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return formatBytea(bytes);
        }
        return "'" + escapeString(value.toString()) + "'";
    }

    /**
     * Renders binary data as a PostgreSQL bytea hex literal ({@code '\xdeadbeef'}) — hex chars
     * only, so no escaping hazards.
     */
    private String formatBytea(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2 + 4);
        hex.append("'\\x");
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.append("'").toString();
    }
}
