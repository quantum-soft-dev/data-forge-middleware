package com.bitbi.dfm.comparison.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that all required database indexes for file comparison feature exist.
 * Runs at application startup to detect missing indexes early.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseIndexVerifier {

    private final JdbcTemplate jdbcTemplate;

    // Expected indexes from V16 migration
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "idx_file_comparisons_account_id",
            "idx_file_comparisons_current_batch",
            "idx_file_comparisons_target_batch",
            "idx_file_comparisons_status",
            "idx_file_comparisons_created_at",
            "idx_file_comparisons_account_created",
            "idx_comparison_results_comparison_id",
            "idx_comparison_results_file_id",
            "idx_comparison_results_change_type",
            "idx_comparison_results_comparison_change",
            "idx_comparison_results_diff"
    );

    /**
     * Verifies all required indexes exist at application startup.
     * Logs warnings for missing indexes but does not prevent startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyIndexesOnStartup() {
        log.info("Verifying database indexes for file comparison tables");

        Map<String, Boolean> indexStatus = verifyIndexes();

        long missingCount = indexStatus.values().stream().filter(exists -> !exists).count();

        if (missingCount == 0) {
            log.info("✓ All {} required indexes for file comparison feature exist", REQUIRED_INDEXES.size());
        } else {
            log.warn("⚠ Missing {} of {} required indexes for file comparison feature",
                    missingCount, REQUIRED_INDEXES.size());
            indexStatus.entrySet().stream()
                    .filter(entry -> !entry.getValue())
                    .forEach(entry -> log.warn("  Missing index: {}", entry.getKey()));
            log.warn("Query performance may be degraded. Run Flyway migration V16 to create missing indexes.");
        }
    }

    /**
     * Checks which required indexes exist in the database.
     *
     * @return Map of index name to existence status (true = exists, false = missing)
     */
    public Map<String, Boolean> verifyIndexes() {
        String query = """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND (tablename = 'file_comparisons' OR tablename = 'comparison_results')
                """;

        List<String> existingIndexes = jdbcTemplate.queryForList(query, String.class);

        return REQUIRED_INDEXES.stream()
                .collect(java.util.stream.Collectors.toMap(
                        indexName -> indexName,
                        existingIndexes::contains
                ));
    }

    /**
     * Returns health check status for comparison database indexes.
     *
     * @return true if all indexes exist, false if any are missing
     */
    public boolean areAllIndexesPresent() {
        return verifyIndexes().values().stream().allMatch(exists -> exists);
    }
}
