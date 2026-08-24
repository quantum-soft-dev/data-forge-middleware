package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapping from a site's declared PostgreSQL column type (and a single-letter DBF code)
 * onto {@link DbfColumnType}, which is how DBF SQL generation learns a column is numeric
 * rather than treating every CSV cell as CHARACTER.
 */
@DisplayName("DbfColumnType")
class DbfColumnTypeTest {

    @Nested
    @DisplayName("fromSqlType")
    class FromSqlType {

        @ParameterizedTest(name = "{0} → INTEGER")
        @ValueSource(strings = {
                "integer", "INTEGER", "int", "int4", "serial",
                "smallint", "int2", "smallserial",
                "bigint", "int8", "bigserial"
        })
        void shouldMapIntegerFamilyToInteger(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.INTEGER);
        }

        @ParameterizedTest(name = "{0} → NUMERIC")
        @ValueSource(strings = {"numeric", "NUMERIC", "decimal", "numeric(12,2)", "decimal(10,4)"})
        void shouldMapNumericFamilyToNumeric(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.NUMERIC);
        }

        @Test
        @DisplayName("should map money to CURRENCY (empty cell → 0)")
        void shouldMapMoneyToCurrency() {
            assertThat(DbfColumnType.fromSqlType("money")).isEqualTo(DbfColumnType.CURRENCY);
        }

        @ParameterizedTest(name = "{0} → FLOAT")
        @ValueSource(strings = {
                "real", "float4", "float",
                "double precision", "DOUBLE PRECISION", "float8", "double"
        })
        void shouldMapFloatingFamilyToFloat(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.FLOAT);
        }

        @ParameterizedTest(name = "{0} → LOGICAL")
        @ValueSource(strings = {"boolean", "bool", "BOOLEAN"})
        void shouldMapBooleanToLogical(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.LOGICAL);
        }

        @Test
        @DisplayName("should map date to DATE")
        void shouldMapDate() {
            assertThat(DbfColumnType.fromSqlType("date")).isEqualTo(DbfColumnType.DATE);
        }

        @ParameterizedTest(name = "{0} → DATETIME")
        @ValueSource(strings = {
                "timestamp", "timestamptz", "datetime",
                "timestamp without time zone", "timestamp with time zone",
                "timestamp(6) with time zone"
        })
        void shouldMapTimestampFamilyToDateTime(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.DATETIME);
        }

        @ParameterizedTest(name = "{0} → CHARACTER")
        @ValueSource(strings = {"varchar", "varchar(255)", "text", "uuid", "bytea", "jsonb", "unknown_type"})
        void shouldDefaultUnknownPostgresTypesToCharacter(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.CHARACTER);
        }

        @ParameterizedTest(name = "DBF code {0} → {1}")
        @CsvSource({
                "C, CHARACTER",
                "N, NUMERIC",
                "I, INTEGER",
                "Y, CURRENCY",
                "F, FLOAT",
                "L, LOGICAL",
                "D, DATE",
                "T, DATETIME"
        })
        void shouldAcceptSingleLetterDbfCodes(String code, DbfColumnType expected) {
            assertThat(DbfColumnType.fromSqlType(code)).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        void shouldDefaultBlankToCharacter(String sqlType) {
            assertThat(DbfColumnType.fromSqlType(sqlType)).isEqualTo(DbfColumnType.CHARACTER);
        }
    }
}
