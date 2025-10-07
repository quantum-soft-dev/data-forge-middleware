package com.bitbi.dfm.error.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PartitionManager domain service.
 */
@DisplayName("PartitionManager Domain Service Unit Tests")
class PartitionManagerTest {

    private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = new PartitionManager();
    }

    @Test
    @DisplayName("Should generate partition name with correct format")
    void shouldGeneratePartitionNameWithCorrectFormat() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 15);

        // When
        String partitionName = partitionManager.generatePartitionName(date);

        // Then
        assertEquals("error_logs_2025_10", partitionName);
    }

    @Test
    @DisplayName("Should generate partition name for January")
    void shouldGeneratePartitionNameForJanuary() {
        // Given
        LocalDate date = LocalDate.of(2025, 1, 1);

        // When
        String partitionName = partitionManager.generatePartitionName(date);

        // Then
        assertEquals("error_logs_2025_01", partitionName);
    }

    @Test
    @DisplayName("Should generate partition name for December")
    void shouldGeneratePartitionNameForDecember() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 31);

        // When
        String partitionName = partitionManager.generatePartitionName(date);

        // Then
        assertEquals("error_logs_2025_12", partitionName);
    }

    @Test
    @DisplayName("Should throw exception when date is null for partition name")
    void shouldThrowExceptionWhenDateIsNullForPartitionName() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                partitionManager.generatePartitionName(null)
        );
    }

    @Test
    @DisplayName("Should generate correct SQL for partition creation")
    void shouldGenerateCorrectSqlForPartitionCreation() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 15);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertNotNull(sql);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS error_logs_2025_10"));
        assertTrue(sql.contains("PARTITION OF error_logs"));
        assertTrue(sql.contains("FOR VALUES FROM ('2025-10-01') TO ('2025-11-01')"));
    }

    @Test
    @DisplayName("Should generate SQL with correct date range for January")
    void shouldGenerateSqlWithCorrectDateRangeForJanuary() {
        // Given
        LocalDate date = LocalDate.of(2025, 1, 10);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("FOR VALUES FROM ('2025-01-01') TO ('2025-02-01')"));
    }

    @Test
    @DisplayName("Should generate SQL with correct date range for December")
    void shouldGenerateSqlWithCorrectDateRangeForDecember() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 15);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("FOR VALUES FROM ('2025-12-01') TO ('2026-01-01')"));
    }

    @Test
    @DisplayName("Should handle leap year correctly in SQL generation")
    void shouldHandleLeapYearCorrectlyInSqlGeneration() {
        // Given
        LocalDate date = LocalDate.of(2024, 2, 15); // 2024 is a leap year

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("FOR VALUES FROM ('2024-02-01') TO ('2024-03-01')"));
    }

    @Test
    @DisplayName("Should throw exception when date is null for SQL generation")
    void shouldThrowExceptionWhenDateIsNullForSqlGeneration() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                partitionManager.generatePartitionCreationSql(null)
        );
    }

    @Test
    @DisplayName("Should return next month for next partition date")
    void shouldReturnNextMonthForNextPartitionDate() {
        // When
        LocalDate nextPartitionDate = partitionManager.getNextPartitionDate();

        // Then
        assertNotNull(nextPartitionDate);
        LocalDate expectedDate = LocalDate.now().plusMonths(1);
        assertEquals(expectedDate.getYear(), nextPartitionDate.getYear());
        assertEquals(expectedDate.getMonth(), nextPartitionDate.getMonth());
    }

    @Test
    @DisplayName("Should return partition name to check")
    void shouldReturnPartitionNameToCheck() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 15);

        // When
        String partitionName = partitionManager.getPartitionNameToCheck(date);

        // Then
        assertEquals("error_logs_2025_10", partitionName);
    }

    @Test
    @DisplayName("Should calculate retention cutoff date correctly")
    void shouldCalculateRetentionCutoffDateCorrectly() {
        // Given
        int retentionMonths = 6;
        LocalDate expectedCutoff = LocalDate.now().minusMonths(6);

        // When
        LocalDate cutoffDate = partitionManager.calculateRetentionCutoffDate(retentionMonths);

        // Then
        assertEquals(expectedCutoff.getYear(), cutoffDate.getYear());
        assertEquals(expectedCutoff.getMonth(), cutoffDate.getMonth());
        assertEquals(expectedCutoff.getDayOfMonth(), cutoffDate.getDayOfMonth());
    }

    @Test
    @DisplayName("Should calculate retention cutoff for 12 months")
    void shouldCalculateRetentionCutoffFor12Months() {
        // Given
        int retentionMonths = 12;
        LocalDate expectedCutoff = LocalDate.now().minusMonths(12);

        // When
        LocalDate cutoffDate = partitionManager.calculateRetentionCutoffDate(retentionMonths);

        // Then
        assertEquals(expectedCutoff.getYear(), cutoffDate.getYear());
        assertEquals(expectedCutoff.getMonth(), cutoffDate.getMonth());
    }

    @Test
    @DisplayName("Should throw exception when retention months is zero")
    void shouldThrowExceptionWhenRetentionMonthsIsZero() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                partitionManager.calculateRetentionCutoffDate(0)
        );
        assertEquals("Retention months must be positive", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when retention months is negative")
    void shouldThrowExceptionWhenRetentionMonthsIsNegative() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                partitionManager.calculateRetentionCutoffDate(-1)
        );
        assertEquals("Retention months must be positive", exception.getMessage());
    }

    @Test
    @DisplayName("Should use correct partition name prefix")
    void shouldUseCorrectPartitionNamePrefix() {
        // Given
        LocalDate date = LocalDate.of(2025, 5, 1);

        // When
        String partitionName = partitionManager.generatePartitionName(date);

        // Then
        assertTrue(partitionName.startsWith("error_logs_"));
    }

    @Test
    @DisplayName("Should generate SQL with IF NOT EXISTS clause")
    void shouldGenerateSqlWithIfNotExistsClause() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 1);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    @DisplayName("Should handle year boundary correctly")
    void shouldHandleYearBoundaryCorrectly() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 31);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("error_logs_2025_12"));
        assertTrue(sql.contains("('2025-12-01') TO ('2026-01-01')"));
    }

    @Test
    @DisplayName("Should generate consistent partition names for same month")
    void shouldGenerateConsistentPartitionNamesForSameMonth() {
        // Given
        LocalDate date1 = LocalDate.of(2025, 10, 1);
        LocalDate date2 = LocalDate.of(2025, 10, 15);
        LocalDate date3 = LocalDate.of(2025, 10, 31);

        // When
        String name1 = partitionManager.generatePartitionName(date1);
        String name2 = partitionManager.generatePartitionName(date2);
        String name3 = partitionManager.generatePartitionName(date3);

        // Then
        assertEquals(name1, name2);
        assertEquals(name2, name3);
        assertEquals("error_logs_2025_10", name1);
    }

    @Test
    @DisplayName("Should use underscore separator in partition names")
    void shouldUseUnderscoreSeparatorInPartitionNames() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 1);

        // When
        String partitionName = partitionManager.generatePartitionName(date);

        // Then
        assertEquals("error_logs_2025_10", partitionName);
        assertFalse(partitionName.contains("-"));
        assertTrue(partitionName.contains("_"));
    }

    @Test
    @DisplayName("Should use hyphen separator in SQL date ranges")
    void shouldUseHyphenSeparatorInSqlDateRanges() {
        // Given
        LocalDate date = LocalDate.of(2025, 10, 1);

        // When
        String sql = partitionManager.generatePartitionCreationSql(date);

        // Then
        assertTrue(sql.contains("2025-10-01"));
        assertTrue(sql.contains("2025-11-01"));
    }
}
