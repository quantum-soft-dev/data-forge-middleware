package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Issue #164 — SQL generation must not wait on its semaphore or talk to S3 with a database
 * transaction open, and the Phase 1 / Phase 3 methods that claimed a transaction boundary
 * must actually have one (they were {@code protected} self-invocations, so the annotations
 * were inert).
 */
class SqlGenerationOutsideTransactionTest {

    private SqlGenerationService service;

    @BeforeEach
    void setUp() {
        service = new SqlGenerationService(
                mock(AccountPluginRepository.class),
                new SqlGenerationPersistence(
                        mock(BatchRepository.class),
                        mock(SiteRepository.class),
                        mock(PluginSqlGenerationRepository.class),
                        mock(ChangelogSegmentRepository.class)),
                mock(S3SqlFileStorageService.class),
                new SimpleMeterRegistry(),
                mock(PluginAuditService.class),
                mock(DbfSqlGenerationStrategy.class),
                mock(CdcSqlGenerationStrategy.class),
                mock(SiteSchemaService.class),
                mock(DeltaSqlGenerationStrategy.class),
                mock(PluginDeltaBaselineRepository.class),
                2, 1, 80);
        service.init();
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void generateSqlForBatchRefusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.generateSqlForBatch(UUID.randomUUID(), 1L));
        assertTrue(thrown.getMessage().contains("transaction"), thrown.getMessage());
    }

    @Test
    void persistenceCollaboratorCarriesTheRealTransactionBoundary() throws Exception {
        Method load = SqlGenerationPersistence.class.getDeclaredMethod("loadBatchData", UUID.class, boolean.class);
        Method save = SqlGenerationPersistence.class.getDeclaredMethod("saveGenerationRecord",
                Long.class, SqlGenerationPersistence.BatchData.class, String.class, long.class,
                SqlGenerationStats.class, long.class);

        Transactional loadTx = load.getAnnotation(Transactional.class);
        Transactional saveTx = save.getAnnotation(Transactional.class);
        assertNotNull(loadTx, "loadBatchData must be transactional on the collaborator, not self-invoked");
        assertTrue(loadTx.readOnly());
        assertNotNull(saveTx, "saveGenerationRecord must be transactional on the collaborator, not self-invoked");
        assertFalse(saveTx.readOnly());
    }
}
