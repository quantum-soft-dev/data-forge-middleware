package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.ValueMapper;
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
     * A decimal / scientific token that is safe to emit unquoted as a PostgreSQL numeric
     * literal. Anything else on the numeric branch is quoted and escaped — a cell such as
     * {@code 0); DROP TABLE customers; --} must never go into SQL raw (issue #263).
     */
    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");

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
     *
     * <p>A non-finite token in a numeric column is quoted, for the reason
     * {@link #formatJsonValue(Object)} explains: unquoted, {@code NaN} is an identifier to
     * PostgreSQL, not a literal. Quoting makes the statement <em>valid</em> only where the target
     * type accepts a non-finite value — {@code numeric}, {@code real} and {@code double precision}
     * do, an integral one does not, and there the statement fails either way
     * ({@code invalid input syntax for type integer} in place of {@code column "nan" does not
     * exist}); {@link DbfColumnType#INTEGER} and {@link DbfColumnType#CURRENCY} are quoted with the
     * rest because a uniform rule is never worse than the bare token, not because it rescues them.</p>
     *
     * <p>{@link DbfSqlGenerationStrategy} supplies types from the site's {@code TableSchema}.
     * A numeric cell is emitted unquoted only when it is numeric-shaped (or a quoted non-finite
     * spelling). Any other token is quoted and escaped, so a CSV cell such as
     * {@code 0); DROP TABLE customers; --} cannot become raw SQL (issue #263).</p>
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

        // Numeric types - unquoted only when the cell is a number (or a quoted non-finite)
        if (isNumericType(type)) {
            String nonFinite = ValueMapper.canonicalNonFinite(value);
            if (nonFinite != null) {
                return "'" + nonFinite + "'";
            }
            String trimmed = value.trim();
            if (isNumericLiteral(trimmed)) {
                return trimmed;
            }
            return "'" + escapeString(value) + "'";
        }

        // String types - escape and quote
        return "'" + escapeString(value) + "'";
    }

    private boolean isNumericLiteral(String value) {
        return NUMERIC_LITERAL.matcher(value).matches();
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
     *
     * <p>A non-finite {@code double} is the one number that must be <em>quoted</em>: PostgreSQL
     * {@code real} / {@code double precision} hold {@code NaN} and {@code +/-Infinity}, but only as
     * a string literal it coerces to the column type — bare, {@code NaN} parses as a column name
     * ({@code ERROR: column "nan" does not exist}), so the statement was written and uploaded
     * successfully and failed only when Bit BI applied the file, taking the rest of the file with it
     * if it applies one transactionally (issue #233).</p>
     *
     * <p>Quoted rather than degraded to NULL, which is what the {@code numeric} path does for the
     * same three values (#215). The trade-off runs the other way here: Parquet DECIMAL cannot hold
     * a non-finite value, so NULL keeps the Parquet artifacts and the SQL stream agreeing about that
     * cell, whereas Parquet DOUBLE holds it natively — nulling it in SQL alone would be the
     * disagreement #215 avoided, and this pipeline would be dropping a value both of its consumers
     * can carry. The same property makes such a value usable as a key: PostgreSQL compares
     * {@code NaN} equal to itself, so {@code col = 'NaN'} addresses the row, which is why
     * {@code DeltaSqlGenerationStrategy} skips a record only for an unrepresentable
     * <em>decimal</em> key.</p>
     *
     * <p>This method still keys on the <em>wire</em> type because it has no schema. A column
     * declared {@code numeric(p,s)} whose value nevertheless arrives as {@code double_value} is
     * degraded to {@code null} <em>before</em> it reaches here, by
     * {@code DeltaSqlGenerationStrategy} (issue #240): Parquet DECIMAL cannot hold a non-finite
     * value, so NULL is the contract both consumers keep. A {@code double precision} or bare
     * {@code numeric} destination is not a DECIMAL and still arrives as a {@code Double}, quoted
     * below.</p>
     */
    private String formatJsonValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            // One vocabulary for the three spellings, shared with the DBF branch and with
            // ChangelogFold rather than restated here -- a second copy of it is what issue #238 was.
            // Java prints exactly PostgreSQL's own spellings, so the round trip through the token is
            // free: the same string is returned unquoted when the value is finite.
            String text = value.toString();
            String nonFinite = ValueMapper.canonicalNonFinite(text);
            return nonFinite != null ? "'" + nonFinite + "'" : text;
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
