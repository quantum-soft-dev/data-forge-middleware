package com.bitbi.dfm.error.domain;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Domain service for managing error_logs table partitions.
 * <p>
 * Encapsulates business logic for time-based partition creation
 * and naming conventions.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class PartitionManager {

    private static final String PARTITION_NAME_PREFIX = "error_logs_";
    private static final DateTimeFormatter PARTITION_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter PARTITION_RANGE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Generate partition name for given date.
     * <p>
     * Format: error_logs_YYYY_MM (e.g., error_logs_2025_10)
     * </p>
     *
     * @param date date for partition
     * @return partition name
     */
    public String generatePartitionName(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return PARTITION_NAME_PREFIX + date.format(PARTITION_NAME_FORMATTER);
    }

    /**
     * Generate SQL for creating monthly partition.
     * <p>
     * Creates partition for date range: [YYYY-MM-01, YYYY-(MM+1)-01)
     * </p>
     *
     * @param date month to create partition for
     * @return SQL CREATE TABLE statement
     */
    public String generatePartitionCreationSql(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");

        String partitionName = generatePartitionName(date);
        LocalDate startDate = date.withDayOfMonth(1);
        LocalDate endDate = startDate.plusMonths(1);

        String startDateStr = startDate.format(PARTITION_RANGE_FORMATTER);
        String endDateStr = endDate.format(PARTITION_RANGE_FORMATTER);

        return String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF error_logs " +
                        "FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, startDateStr, endDateStr
        );
    }

    /**
     * Calculate which partition should be created next.
     * <p>
     * Returns: current month + 1 (one month in advance)
     * </p>
     *
     * @return date for next partition to create
     */
    public LocalDate getNextPartitionDate() {
        return LocalDate.now().plusMonths(1);
    }

    /**
     * Check if partition exists for given date (to be implemented by infrastructure).
     * This is a domain concept that will be implemented by infrastructure layer.
     *
     * @param date date to check
     * @return partition name to check
     */
    public String getPartitionNameToCheck(LocalDate date) {
        return generatePartitionName(date);
    }

    /**
     * Calculate partition retention date based on retention policy.
     * <p>
     * For now, we don't delete old partitions (indefinite retention).
     * This method is placeholder for future retention policy implementation.
     * </p>
     *
     * @param retentionMonths number of months to retain
     * @return cutoff date for partition deletion
     */
    public LocalDate calculateRetentionCutoffDate(int retentionMonths) {
        if (retentionMonths <= 0) {
            throw new IllegalArgumentException("Retention months must be positive");
        }
        return LocalDate.now().minusMonths(retentionMonths);
    }
}
