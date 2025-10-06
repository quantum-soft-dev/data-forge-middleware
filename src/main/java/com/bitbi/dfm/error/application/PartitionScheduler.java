package com.bitbi.dfm.error.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Scheduled task to create error_logs partitions in advance.
 * <p>
 * Runs monthly to create partitions one month ahead, ensuring
 * error logs always have a target partition.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class PartitionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PartitionScheduler.class);
    private static final DateTimeFormatter PARTITION_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcTemplate jdbcTemplate;

    public PartitionScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Create error_logs partition for next month.
     * <p>
     * Runs on the 1st day of each month at 00:00:00.
     * Cron expression: 0 0 0 1 * * (midnight on the first day of every month)
     * </p>
     */
    @Scheduled(cron = "0 0 0 1 * *")
    public void createNextMonthPartition() {
        YearMonth nextMonth = YearMonth.now().plusMonths(1);
        createPartition(nextMonth);
    }

    /**
     * Create partition for specific year-month.
     *
     * @param yearMonth year-month to create partition for
     */
    public void createPartition(YearMonth yearMonth) {
        String partitionName = "error_logs_" + yearMonth.format(PARTITION_NAME_FORMATTER);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.plusMonths(1).atDay(1);

        try {
            logger.info("Creating partition: {} (range: {} to {})", partitionName, startDate, endDate);

            String sql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s PARTITION OF error_logs " +
                    "FOR VALUES FROM ('%s') TO ('%s')",
                    partitionName,
                    startDate,
                    endDate
            );

            jdbcTemplate.execute(sql);

            logger.info("Successfully created partition: {}", partitionName);

        } catch (Exception e) {
            // Log error but don't fail - partition might already exist
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                logger.debug("Partition already exists: {}", partitionName);
            } else {
                logger.error("Failed to create partition: {}, error: {}", partitionName, e.getMessage(), e);
            }
        }
    }

    /**
     * Initialize partitions for current and next month.
     * <p>
     * Called on application startup to ensure partitions exist.
     * </p>
     */
    public void initializePartitions() {
        logger.info("Initializing error_logs partitions");

        YearMonth currentMonth = YearMonth.now();
        YearMonth nextMonth = currentMonth.plusMonths(1);

        createPartition(currentMonth);
        createPartition(nextMonth);

        logger.info("Partition initialization completed");
    }
}
