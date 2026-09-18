package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upload History reads the database on every call, and the application carries no cache and no
 * Redis (issue #319).
 *
 * <p>The two Upload History caches ({@code batch-details}, {@code batch-first-page}) held nothing for
 * the life of the feature, so removing them changes no observable behaviour — what this class pins
 * is that it stays that way on the path where a cache would have been wrong. The detail entry was
 * keyed by the batch id alone while the owner check runs inside {@code getBatchDetails}, so a live
 * entry would have answered a second account from the first account's read; and a batch is not
 * immutable once {@code COMPLETED} (deletion, retention and a site wipe all change what it reads).
 * The static half — no cache annotation, no Redis dependency, no Redis configuration — is
 * {@code NoSpringCacheConventionTest}; this is the wired half, which only the running context can
 * show.</p>
 */
@DisplayName("#319 — Upload History without a cache")
class BatchHistoryWithoutCacheIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID COMPLETED_BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BatchHistoryService batchHistoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the context holds no cache manager and no Redis bean — no health contributor for a store nothing uses")
    void contextHasNoCacheManagerAndNoRedis() {
        assertThat(applicationContext.getBeanNamesForType(CacheManager.class)).isEmpty();
        assertThat(Arrays.stream(applicationContext.getBeanDefinitionNames())
                .filter(name -> name.toLowerCase().contains("redis")))
                .isEmpty();
    }

    @Test
    @DisplayName("another account is refused a COMPLETED batch its owner has just read")
    void ownerCheckRunsOnEveryRead() {
        assertThat(batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, ACCOUNT_ID).status())
                .isEqualTo("COMPLETED");

        UUID otherAccount = UUID.randomUUID();
        assertThatThrownBy(() -> batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, otherAccount))
                .isInstanceOf(UnauthorizedBatchAccessException.class);
    }

    @Test
    @DisplayName("a second read of a COMPLETED batch and of the first page sees a change made in between")
    void secondReadSeesTheDatabase() {
        assertThat(batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, ACCOUNT_ID).totalSize())
                .isEqualTo(2048L);
        assertThat(firstPageTotalSize()).isEqualTo(2048L);

        jdbcTemplate.update("UPDATE batches SET total_size = 4096 WHERE id = ?", COMPLETED_BATCH_ID);

        assertThat(batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, ACCOUNT_ID).totalSize())
                .isEqualTo(4096L);
        assertThat(firstPageTotalSize()).isEqualTo(4096L);
    }

    private Long firstPageTotalSize() {
        return batchHistoryService.listBatchHistory(ACCOUNT_ID, null, 100).items().stream()
                .filter(batch -> batch.id().equals(COMPLETED_BATCH_ID))
                .map(BatchSummaryDto::totalSize)
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded batch missing from the first page"));
    }
}
