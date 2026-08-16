package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #164 — the delta-SQL worker must not hold a HikariCP connection across the generation
 * semaphore (up to 120 s) or the S3 I/O that follows. {@code processNextPending} therefore
 * opens no transaction of its own: the pending-row claim, the skip/snapshot path and the
 * {@code plugin_sql_at} write are short repository transactions, and generation is invoked
 * with nothing open.
 */
class DeltaSqlQueueOutsideTransactionTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Long ACTIVATION_ID = 5L;

    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final AccountPluginRepository accountPluginRepository = mock(AccountPluginRepository.class);
    private final SqlGenerationService sqlGenerationService = mock(SqlGenerationService.class);
    private DeltaSqlQueueService queueService;
    private ChangelogSegment segment;

    @BeforeEach
    void setUp() {
        queueService = new DeltaSqlQueueService(
                segmentRepository, siteRepository, accountPluginRepository,
                sqlGenerationService, mock(PluginDeltaBaselineRepository.class),
                mock(PluginAuditService.class), new SimpleMeterRegistry());

        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);
        AccountPlugin activation = mock(AccountPlugin.class);
        when(activation.getId()).thenReturn(ACTIVATION_ID);
        when(activation.isActive()).thenReturn(true);
        when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(site));
        when(accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(activation));

        segment = ChangelogSegment.create(SITE_ID, BATCH_ID, 1L, 1L, 1L, "hash", "delta/x", "DELTA", Map.of());
        when(segmentRepository.findNextPendingPluginSql(1)).thenReturn(List.of(segment));
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void processNextPendingIsNotTransactional() throws Exception {
        Method method = DeltaSqlQueueService.class.getMethod("processNextPending");
        assertNull(method.getAnnotation(Transactional.class),
                "processNextPending must not pin a connection across the semaphore or S3 (issue #164)");
    }

    @Test
    void generateSqlForBatchRunsWithNoAmbientTransaction() {
        AtomicBoolean generationSawTransaction = new AtomicBoolean(true);
        when(sqlGenerationService.generateSqlForBatch(any(), any())).thenAnswer(inv -> {
            generationSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        });

        assertTrue(queueService.processNextPending());
        assertFalse(generationSawTransaction.get(),
                "generation (semaphore + S3) must run after the claim transaction has closed");
        assertTrue(segment.getPluginSqlAt() != null);
    }
}
