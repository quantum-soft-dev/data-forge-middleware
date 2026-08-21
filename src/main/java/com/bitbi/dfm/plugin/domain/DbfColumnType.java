package com.bitbi.dfm.plugin.domain;

import java.util.Locale;

/**
 * DBF (dBASE) column types for determining NULL handling in SQL generation.
 * <p>
 * Per specification:
 * - Character, Numeric, Logical, Date, Float, DateTime: empty string = NULL
 * - Integer, Currency: empty string = 0
 * </p>
 * A site's {@code TableSchema} declares PostgreSQL types; {@link #fromSqlType(String)}
 * maps those onto this enum so DBF SQL generation can apply the table above.
 */
public enum DbfColumnType {
    /** Character field - empty = NULL */
    CHARACTER("C", true),
    /** Numeric field - empty = NULL */
    NUMERIC("N", true),
    /** Logical/Boolean field - empty = NULL */
    LOGICAL("L", true),
    /** Date field - empty = NULL */
    DATE("D", true),
    /** Float field - empty = NULL */
    FLOAT("F", true),
    /** DateTime field - empty = NULL */
    DATETIME("T", true),
    /** Integer field - empty = 0 */
    INTEGER("I", false),
    /** Currency field - empty = 0 */
    CURRENCY("Y", false);

    private final String code;
    private final boolean emptyIsNull;

    DbfColumnType(String code, boolean emptyIsNull) {
        this.code = code;
        this.emptyIsNull = emptyIsNull;
    }

    /**
     * Returns the single-character DBF type code.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns true if empty strings should be converted to NULL for this type.
     * Returns false if empty strings should be converted to 0.
     */
    public boolean isEmptyNull() {
        return emptyIsNull;
    }

    /**
     * Parses a DBF type code to enum value.
     * Returns CHARACTER as default if code is not recognized.
     *
     * @param code Single-character DBF type code (e.g., "C", "N", "I")
     * @return The corresponding DbfColumnType
     */
    public static DbfColumnType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return CHARACTER;
        }
        for (DbfColumnType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return CHARACTER;  // Default to CHARACTER (empty = NULL)
    }

    /**
     * Maps a PostgreSQL column type (as stored on {@code TableSchema}) onto a DBF type.
     *
     * <p>Modifiers in parentheses are ignored ({@code numeric(12,2)} is {@link #NUMERIC}).
     * A single-letter DBF code is accepted as well, so a schema that stored the original
     * header encoding still resolves. Anything unrecognised becomes {@link #CHARACTER}.</p>
     *
     * @param sqlType PostgreSQL type string (e.g. {@code integer}, {@code varchar(255)})
     *                or a one-letter DBF code
     * @return the corresponding DBF type
     */
    public static DbfColumnType fromSqlType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return CHARACTER;
        }
        String trimmed = sqlType.trim();
        String base = trimmed.toLowerCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren >= 0) {
            base = base.substring(0, paren);
        }
        base = base.replaceAll("\\s+", " ").trim();
        return switch (base) {
            case "integer", "int", "int4", "serial",
                 "smallint", "int2", "smallserial",
                 "bigint", "int8", "bigserial" -> INTEGER;
            case "money" -> CURRENCY;
            case "numeric", "decimal" -> NUMERIC;
            case "real", "float4", "float",
                 "double precision", "float8", "double" -> FLOAT;
            case "boolean", "bool" -> LOGICAL;
            case "date" -> DATE;
            case "timestamp", "timestamptz", "datetime",
                 "timestamp without time zone", "timestamp with time zone" -> DATETIME;
            default -> fromCode(trimmed);
        };
    }

    /**
     * Converts an empty string value according to this type's rules.
     *
     * @param value The value to convert
     * @return null if empty and type uses NULL, "0" if empty and type uses 0, original value otherwise
     */
    public String convertEmptyValue(String value) {
        if (value == null || value.isEmpty()) {
            return emptyIsNull ? null : "0";
        }
        return value;
    }
}
