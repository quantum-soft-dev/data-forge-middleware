package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.presentation.dto.TableDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for TableDto.
 * Tests table name derivation from file names.
 */
@DisplayName("TableDto")
class TableDtoTest {

    @Nested
    @DisplayName("deriveTableName")
    class DeriveTableName {

        @Test
        @DisplayName("should remove .csv extension")
        void shouldRemoveCsvExtension() {
            assertThat(TableDto.deriveTableName("customers.csv")).isEqualTo("customers");
        }

        @Test
        @DisplayName("should remove .csv.gz extension")
        void shouldRemoveCsvGzExtension() {
            assertThat(TableDto.deriveTableName("orders.csv.gz")).isEqualTo("orders");
        }

        @Test
        @DisplayName("should keep name without extension unchanged")
        void shouldKeepNameWithoutExtension() {
            assertThat(TableDto.deriveTableName("products")).isEqualTo("products");
        }

        @Test
        @DisplayName("should prefix with underscore when name starts with digit")
        void shouldPrefixWithUnderscoreWhenStartsWithDigit() {
            assertThat(TableDto.deriveTableName("123_data.csv")).isEqualTo("_123_data");
        }

        @Test
        @DisplayName("should prefix with underscore for numeric-only names")
        void shouldPrefixNumericOnlyNames() {
            assertThat(TableDto.deriveTableName("2024.csv")).isEqualTo("_2024");
        }

        @ParameterizedTest
        @DisplayName("should replace invalid characters with underscore")
        @CsvSource({
            "my-file.csv, my_file",
            "my file.csv, my_file",
            "my.file.csv, my_file",
            "data@2024.csv, data_2024",
            "report#1.csv.gz, report_1"
        })
        void shouldReplaceInvalidCharacters(String input, String expected) {
            assertThat(TableDto.deriveTableName(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("should handle complex filename with multiple issues")
        void shouldHandleComplexFilename() {
            // Starts with digit + has special chars
            assertThat(TableDto.deriveTableName("123-report@data.csv.gz")).isEqualTo("_123_report_data");
        }

        @ParameterizedTest
        @DisplayName("should throw exception for blank input")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        void shouldThrowExceptionForBlankInput(String input) {
            assertThatThrownBy(() -> TableDto.deriveTableName(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be blank");
        }

        @Test
        @DisplayName("should throw exception when result is empty after stripping extension")
        void shouldThrowExceptionForEmptyResult() {
            assertThatThrownBy(() -> TableDto.deriveTableName(".csv"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("should throw exception for .csv.gz only")
        void shouldThrowExceptionForCsvGzOnly() {
            assertThatThrownBy(() -> TableDto.deriveTableName(".csv.gz"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @ParameterizedTest
        @DisplayName("should handle various valid filenames")
        @CsvSource({
            "UPPERCASE.csv, UPPERCASE",
            "MixedCase.csv.gz, MixedCase",
            "with_underscore.csv, with_underscore",
            "file123.csv, file123"
        })
        void shouldHandleVariousValidFilenames(String input, String expected) {
            assertThat(TableDto.deriveTableName(input)).isEqualTo(expected);
        }
    }
}
