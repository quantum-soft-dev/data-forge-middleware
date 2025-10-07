package com.bitbi.dfm.error.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PartitionScheduler.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PartitionScheduler Unit Tests")
class PartitionSchedulerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PartitionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PartitionScheduler(jdbcTemplate);
    }

    @Test
    @DisplayName("Should create partition for next month")
    void shouldCreateNextMonthPartition() {
        // When
        scheduler.createNextMonthPartition();

        // Then
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    @DisplayName("Should create partition with correct SQL")
    void shouldCreatePartitionWithCorrectSql() {
        // Given
        YearMonth month = YearMonth.of(2025, 10);

        // When
        scheduler.createPartition(month);

        // Then
        verify(jdbcTemplate).execute(
                eq("CREATE TABLE IF NOT EXISTS error_logs_2025_10 PARTITION OF error_logs " +
                   "FOR VALUES FROM ('2025-10-01') TO ('2025-11-01')")
        );
    }

    @Test
    @DisplayName("Should handle partition creation error gracefully")
    void shouldHandlePartitionCreationError() {
        // Given
        YearMonth month = YearMonth.of(2025, 10);
        doThrow(new RuntimeException("Partition already exists"))
                .when(jdbcTemplate).execute(anyString());

        // When
        scheduler.createPartition(month);

        // Then - should not throw exception
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    @DisplayName("Should drop old partition when it exists")
    void shouldDropOldPartitionWhenExists() {
        // Given
        YearMonth oldMonth = YearMonth.of(2023, 1); // 2 years ago
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(true);

        // When
        scheduler.dropPartition(oldMonth);

        // Then
        verify(jdbcTemplate).queryForObject(
                eq("SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'error_logs_2023_01')"),
                eq(Boolean.class)
        );
        verify(jdbcTemplate).execute(eq("DROP TABLE IF EXISTS error_logs_2023_01"));
    }

    @Test
    @DisplayName("Should skip dropping partition when it does not exist")
    void shouldSkipDroppingNonExistentPartition() {
        // Given
        YearMonth oldMonth = YearMonth.of(2023, 1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(false);

        // When
        scheduler.dropPartition(oldMonth);

        // Then
        verify(jdbcTemplate).queryForObject(anyString(), eq(Boolean.class));
        verify(jdbcTemplate, never()).execute(contains("DROP TABLE"));
    }

    @Test
    @DisplayName("Should handle drop partition error gracefully")
    void shouldHandleDropPartitionError() {
        // Given
        YearMonth oldMonth = YearMonth.of(2023, 1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(true);
        doThrow(new RuntimeException("Table is locked"))
                .when(jdbcTemplate).execute(contains("DROP TABLE"));

        // When
        scheduler.dropPartition(oldMonth);

        // Then - should not throw exception
        verify(jdbcTemplate).execute(anyString());
    }

    @Test
    @DisplayName("Should drop old partitions beyond retention period")
    void shouldDropOldPartitions() {
        // Given
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(false); // Partition doesn't exist (normal case)

        // When
        scheduler.dropOldPartitions();

        // Then - should check for partition 24 months ago
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Boolean.class));
    }

    @Test
    @DisplayName("Should initialize partitions for current and next month")
    void shouldInitializePartitions() {
        // When
        scheduler.initializePartitions();

        // Then - should create 2 partitions
        verify(jdbcTemplate, times(2)).execute(anyString());
    }
}
