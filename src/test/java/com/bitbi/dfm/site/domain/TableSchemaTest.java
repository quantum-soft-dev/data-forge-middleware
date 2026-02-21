package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TableSchema value object.
 */
@DisplayName("TableSchema")
class TableSchemaTest {

    private TableSchema buildSchema(String... columnNames) {
        List<TableSchema.ColumnDefinition> columns = java.util.Arrays.stream(columnNames)
                .map(name -> new TableSchema.ColumnDefinition(name, "varchar(255)", false))
                .toList();
        return new TableSchema(columns, List.of(), List.of());
    }

    @Nested
    @DisplayName("hasColumn()")
    class HasColumn {

        @Test
        @DisplayName("should return true for existing column")
        void shouldReturnTrueForExistingColumn() {
            TableSchema schema = buildSchema("id", "name", "email");

            assertThat(schema.hasColumn("id")).isTrue();
            assertThat(schema.hasColumn("name")).isTrue();
            assertThat(schema.hasColumn("email")).isTrue();
        }

        @Test
        @DisplayName("should return false for unknown column")
        void shouldReturnFalseForUnknownColumn() {
            TableSchema schema = buildSchema("id", "name");

            assertThat(schema.hasColumn("unknown_col")).isFalse();
            assertThat(schema.hasColumn("")).isFalse();
        }

        @Test
        @DisplayName("should be case-sensitive")
        void shouldBeCaseSensitive() {
            TableSchema schema = buildSchema("customer_id");

            assertThat(schema.hasColumn("customer_id")).isTrue();
            assertThat(schema.hasColumn("CUSTOMER_ID")).isFalse();
            assertThat(schema.hasColumn("Customer_Id")).isFalse();
        }

        @Test
        @DisplayName("should return false when columns list is empty")
        void shouldReturnFalseForEmptySchema() {
            TableSchema schema = new TableSchema(List.of(), List.of(), List.of());

            assertThat(schema.hasColumn("anything")).isFalse();
        }
    }

    @Nested
    @DisplayName("findColumn()")
    class FindColumn {

        @Test
        @DisplayName("should return column definition for existing column")
        void shouldReturnColumnForExistingColumn() {
            List<TableSchema.ColumnDefinition> columns = List.of(
                    new TableSchema.ColumnDefinition("id", "integer", false),
                    new TableSchema.ColumnDefinition("name", "varchar(100)", true)
            );
            TableSchema schema = new TableSchema(columns, List.of(), List.of());

            Optional<TableSchema.ColumnDefinition> result = schema.findColumn("id");

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("id");
            assertThat(result.get().type()).isEqualTo("integer");
            assertThat(result.get().nullable()).isFalse();
        }

        @Test
        @DisplayName("should return empty Optional for unknown column")
        void shouldReturnEmptyForUnknownColumn() {
            TableSchema schema = buildSchema("id", "name");

            assertThat(schema.findColumn("non_existent")).isEmpty();
        }

        @Test
        @DisplayName("should return first matching column (no duplicates expected)")
        void shouldReturnFirstMatch() {
            List<TableSchema.ColumnDefinition> columns = List.of(
                    new TableSchema.ColumnDefinition("value", "integer", false),
                    new TableSchema.ColumnDefinition("label", "text", true)
            );
            TableSchema schema = new TableSchema(columns, List.of(), List.of());

            Optional<TableSchema.ColumnDefinition> result = schema.findColumn("label");

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo("text");
        }
    }
}
