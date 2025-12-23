package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.SqlStatementGenerator;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SqlStatementGenerator.
 * Tests PostgreSQL SQL statement generation from CSV diffs.
 *
 * TDD: These tests are written FIRST and should FAIL until implementation.
 */
@DisplayName("SqlStatementGenerator")
class SqlStatementGeneratorTest {

    private SqlStatementGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SqlStatementGenerator();
    }

    @Nested
    @DisplayName("INSERT Statement Generation")
    class InsertStatementGeneration {

        @Test
        @DisplayName("should generate INSERT for added row")
        void shouldGenerateInsertForAddedRow() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "Alice");
            values.put("email", "alice@example.com");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "customers", Map.of());

            // Then
            assertThat(sql).contains("INSERT INTO customers");
            assertThat(sql).contains("(id, name, email)");
            assertThat(sql).contains("VALUES ('1', 'Alice', 'alice@example.com')");
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"customers.csv:2\" ---\n");
        }

        @Test
        @DisplayName("should generate INSERT with NULL for empty CHARACTER field")
        void shouldGenerateInsertWithNullForEmptyCharacterField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "Alice");
            values.put("email", ""); // Empty CHARACTER field
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "name", DbfColumnType.CHARACTER,
                "email", DbfColumnType.CHARACTER
            );

            // When
            String sql = generator.generate(diff, "customers", types);

            // Then
            assertThat(sql).contains("VALUES (1, 'Alice', NULL)");
        }

        @Test
        @DisplayName("should generate INSERT with 0 for empty INTEGER field")
        void shouldGenerateInsertWithZeroForEmptyIntegerField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("quantity", ""); // Empty INTEGER field
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "quantity", DbfColumnType.INTEGER
            );

            // When
            String sql = generator.generate(diff, "orders", types);

            // Then
            assertThat(sql).contains("VALUES (1, 0)");
        }

        @Test
        @DisplayName("should generate INSERT with 0 for empty CURRENCY field")
        void shouldGenerateInsertWithZeroForEmptyCurrencyField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("amount", ""); // Empty CURRENCY field
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "amount", DbfColumnType.CURRENCY
            );

            // When
            String sql = generator.generate(diff, "payments", types);

            // Then
            assertThat(sql).contains("VALUES (1, 0)");
        }
    }

    @Nested
    @DisplayName("UPDATE Statement Generation")
    class UpdateStatementGeneration {

        @Test
        @DisplayName("should generate UPDATE for modified row")
        void shouldGenerateUpdateForModifiedRow() {
            // Given
            Map<String, String> newValues = new LinkedHashMap<>();
            newValues.put("id", "1");
            newValues.put("name", "Alice");
            newValues.put("email", "alice@new.com");
            Map<String, String> changedColumns = Map.of("email", "alice@old.com");
            CsvRowDiff diff = CsvRowDiff.modified(2, newValues, changedColumns);

            // When
            String sql = generator.generate(diff, "customers", Map.of());

            // Then
            assertThat(sql).contains("UPDATE customers SET email = 'alice@new.com'");
            assertThat(sql).contains("WHERE id = '1' AND name = 'Alice'");
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"customers.csv:2\" ---\n");
        }

        @Test
        @DisplayName("should use unchanged columns in WHERE clause")
        void shouldUseUnchangedColumnsInWhereClause() {
            // Given
            Map<String, String> newValues = new LinkedHashMap<>();
            newValues.put("id", "1");
            newValues.put("name", "Alice Updated");
            newValues.put("status", "active");
            Map<String, String> changedColumns = Map.of("name", "Alice");
            CsvRowDiff diff = CsvRowDiff.modified(5, newValues, changedColumns);

            // When
            String sql = generator.generate(diff, "users", Map.of());

            // Then
            assertThat(sql).contains("SET name = 'Alice Updated'");
            assertThat(sql).contains("WHERE id = '1' AND status = 'active'");
            assertThat(sql).doesNotContain("name = 'Alice'"); // Old value should not be in WHERE
        }

        @Test
        @DisplayName("should handle UPDATE with multiple changed columns")
        void shouldHandleUpdateWithMultipleChangedColumns() {
            // Given
            Map<String, String> newValues = new LinkedHashMap<>();
            newValues.put("id", "1");
            newValues.put("name", "Bob");
            newValues.put("email", "bob@new.com");
            Map<String, String> changedColumns = new LinkedHashMap<>();
            changedColumns.put("name", "Alice");
            changedColumns.put("email", "alice@old.com");
            CsvRowDiff diff = CsvRowDiff.modified(2, newValues, changedColumns);

            // When
            String sql = generator.generate(diff, "customers", Map.of());

            // Then
            assertThat(sql).contains("SET name = 'Bob', email = 'bob@new.com'");
            assertThat(sql).contains("WHERE id = '1'");
        }
    }

    @Nested
    @DisplayName("DELETE Statement Generation")
    class DeleteStatementGeneration {

        @Test
        @DisplayName("should generate DELETE for deleted row")
        void shouldGenerateDeleteForDeletedRow() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "Alice");
            values.put("email", "alice@example.com");
            CsvRowDiff diff = CsvRowDiff.deleted(3, values);

            // When
            String sql = generator.generate(diff, "customers", Map.of());

            // Then
            assertThat(sql).contains("DELETE FROM customers");
            assertThat(sql).contains("WHERE id = '1' AND name = 'Alice' AND email = 'alice@example.com'");
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"customers.csv:3\" ---\n");
        }

        @Test
        @DisplayName("should use all columns in DELETE WHERE clause")
        void shouldUseAllColumnsInDeleteWhereClause() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("col1", "val1");
            values.put("col2", "val2");
            values.put("col3", "val3");
            CsvRowDiff diff = CsvRowDiff.deleted(10, values);

            // When
            String sql = generator.generate(diff, "data", Map.of());

            // Then
            assertThat(sql).contains("WHERE col1 = 'val1' AND col2 = 'val2' AND col3 = 'val3'");
        }
    }

    @Nested
    @DisplayName("Value Escaping")
    class ValueEscaping {

        @Test
        @DisplayName("should escape single quotes by doubling")
        void shouldEscapeSingleQuotesByDoubling() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "O'Brien");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "customers", Map.of());

            // Then
            assertThat(sql).contains("'O''Brien'");
        }

        @Test
        @DisplayName("should handle multiple single quotes")
        void shouldHandleMultipleSingleQuotes() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("note", "It's John's car");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "notes", Map.of());

            // Then
            assertThat(sql).contains("'It''s John''s car'");
        }

        @Test
        @DisplayName("should handle backslash in values")
        void shouldHandleBackslashInValues() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("path", "C:\\Users\\Admin");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "files", Map.of());

            // Then
            // PostgreSQL doesn't require backslash escaping in standard string literals
            assertThat(sql).contains("'C:\\Users\\Admin'");
        }
    }

    @Nested
    @DisplayName("End of Command Comment")
    class EndOfCommandComment {

        @Test
        @DisplayName("should append correct comment format after INSERT")
        void shouldAppendCorrectCommentFormatAfterInsert() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(42, values);

            // When
            String sql = generator.generate(diff, "data", Map.of());

            // Then
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"data.csv:42\" ---\n");
        }

        @Test
        @DisplayName("should append correct comment format after UPDATE")
        void shouldAppendCorrectCommentFormatAfterUpdate() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "New");
            CsvRowDiff diff = CsvRowDiff.modified(15, values, Map.of("name", "Old"));

            // When
            String sql = generator.generate(diff, "users", Map.of());

            // Then
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"users.csv:15\" ---\n");
        }

        @Test
        @DisplayName("should append correct comment format after DELETE")
        void shouldAppendCorrectCommentFormatAfterDelete() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.deleted(7, values);

            // When
            String sql = generator.generate(diff, "records", Map.of());

            // Then
            assertThat(sql).endsWith(";\n--- END OF COMMAND \"records.csv:7\" ---\n");
        }
    }

    @Nested
    @DisplayName("Table Name Derivation")
    class TableNameDerivation {

        @Test
        @DisplayName("should use provided table name")
        void shouldUseProvidedTableName() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "my_table", Map.of());

            // Then
            assertThat(sql).contains("INSERT INTO my_table");
        }
    }

    @Nested
    @DisplayName("NULL Handling by Type")
    class NullHandlingByType {

        @Test
        @DisplayName("should use NULL for empty NUMERIC field")
        void shouldUseNullForEmptyNumericField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("amount", "");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "amount", DbfColumnType.NUMERIC
            );

            // When
            String sql = generator.generate(diff, "data", types);

            // Then
            assertThat(sql).contains("VALUES (1, NULL)");
        }

        @Test
        @DisplayName("should use NULL for empty DATE field")
        void shouldUseNullForEmptyDateField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("birth_date", "");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "birth_date", DbfColumnType.DATE
            );

            // When
            String sql = generator.generate(diff, "people", types);

            // Then
            assertThat(sql).contains("VALUES (1, NULL)");
        }

        @Test
        @DisplayName("should use NULL for empty LOGICAL field")
        void shouldUseNullForEmptyLogicalField() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("active", "");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "active", DbfColumnType.LOGICAL
            );

            // When
            String sql = generator.generate(diff, "flags", types);

            // Then
            assertThat(sql).contains("VALUES (1, NULL)");
        }

        @Test
        @DisplayName("should default to NULL for unknown type with empty value")
        void shouldDefaultToNullForUnknownType() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("unknown_col", "");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When - no type specified
            String sql = generator.generate(diff, "data", Map.of());

            // Then - should default to CHARACTER behavior (NULL)
            assertThat(sql).contains("VALUES ('1', NULL)");
        }
    }

    @Nested
    @DisplayName("Integer Type Formatting")
    class IntegerTypeFormatting {

        @Test
        @DisplayName("should format INTEGER without quotes")
        void shouldFormatIntegerWithoutQuotes() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "42");
            values.put("quantity", "100");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "quantity", DbfColumnType.INTEGER
            );

            // When
            String sql = generator.generate(diff, "items", types);

            // Then
            assertThat(sql).contains("VALUES (42, 100)");
            assertThat(sql).doesNotContain("'42'");
        }

        @Test
        @DisplayName("should format CURRENCY without quotes")
        void shouldFormatCurrencyWithoutQuotes() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("price", "99.99");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "price", DbfColumnType.CURRENCY
            );

            // When
            String sql = generator.generate(diff, "products", types);

            // Then
            assertThat(sql).contains("VALUES (1, 99.99)");
        }

        @Test
        @DisplayName("should format NUMERIC without quotes")
        void shouldFormatNumericWithoutQuotes() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("weight", "45.5");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "weight", DbfColumnType.NUMERIC
            );

            // When
            String sql = generator.generate(diff, "items", types);

            // Then
            assertThat(sql).contains("VALUES (1, 45.5)");
        }

        @Test
        @DisplayName("should format FLOAT without quotes")
        void shouldFormatFloatWithoutQuotes() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("ratio", "0.75");
            CsvRowDiff diff = CsvRowDiff.added(2, values);
            Map<String, DbfColumnType> types = Map.of(
                "id", DbfColumnType.INTEGER,
                "ratio", DbfColumnType.FLOAT
            );

            // When
            String sql = generator.generate(diff, "metrics", types);

            // Then
            assertThat(sql).contains("VALUES (1, 0.75)");
        }
    }

    @Nested
    @DisplayName("SQL Injection Prevention")
    class SqlInjectionPrevention {

        @Test
        @DisplayName("should reject table name with SQL injection attempt")
        void shouldRejectTableNameWithSqlInjection() {
            // Given - malicious table name
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, "users; DROP TABLE users; --", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains invalid characters");
        }

        @Test
        @DisplayName("should reject column name with SQL injection attempt")
        void shouldRejectColumnNameWithSqlInjection() {
            // Given - malicious column name
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name'; DROP TABLE users; --", "evil");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, "users", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains invalid characters");
        }

        @Test
        @DisplayName("should reject table name with special characters")
        void shouldRejectTableNameWithSpecialCharacters() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, "table-name", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains invalid characters");
        }

        @Test
        @DisplayName("should reject table name starting with number")
        void shouldRejectTableNameStartingWithNumber() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, "123table", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains invalid characters");
        }

        @Test
        @DisplayName("should accept valid table name with underscores")
        void shouldAcceptValidTableNameWithUnderscores() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "my_table_name", Map.of());

            // Then
            assertThat(sql).contains("INSERT INTO my_table_name");
        }

        @Test
        @DisplayName("should accept table name starting with underscore")
        void shouldAcceptTableNameStartingWithUnderscore() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "_temp_table", Map.of());

            // Then
            assertThat(sql).contains("INSERT INTO _temp_table");
        }

        @Test
        @DisplayName("should properly escape SQL injection in values")
        void shouldProperlyEscapeSqlInjectionInValues() {
            // Given - malicious value (should be escaped, not rejected)
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            values.put("name", "John'; DROP TABLE users; --");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When
            String sql = generator.generate(diff, "users", Map.of());

            // Then - value should be escaped with doubled single quotes
            assertThat(sql).contains("'John''; DROP TABLE users; --'");
            assertThat(sql).doesNotContain("John'; DROP");
        }

        @Test
        @DisplayName("should reject null table name")
        void shouldRejectNullTableName() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("should reject empty table name")
        void shouldRejectEmptyTableName() {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", "1");
            CsvRowDiff diff = CsvRowDiff.added(2, values);

            // When / Then
            assertThatThrownBy(() -> generator.generate(diff, "", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }
    }
}
