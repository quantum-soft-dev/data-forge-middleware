package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao.CatalogRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #164 / #176 — {@code listFiles} must not probe S3 while the catalog transaction is
 * open. The catalog page is loaded transactionally; existence probes run after it returns.
 * Listing semantics (drop only on known absence; dropped candidates still advance the
 * cursor) stay with {@link ParquetExportFileServiceTest}.
 */
class ParquetExportFileServiceOutsideTransactionTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 7, 20, 10, 0);

    private final ParquetExportCatalogDao catalogDao = mock(ParquetExportCatalogDao.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private ParquetExportFileService service;

    @BeforeEach
    void setUp() {
        service = new ParquetExportFileService(new ParquetExportCatalogQuery(catalogDao), checkpointStorage);
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void listFilesIsNotTransactional() throws Exception {
        Method listFiles = ParquetExportFileService.class.getMethod("listFiles",
                UUID.class, LocalDateTime.class, UUID.class, String.class,
                ParquetExportFileService.FileType.class, String.class, int.class);
        assertNull(listFiles.getAnnotation(Transactional.class),
                "listFiles must not pin a connection across the S3 probe (issue #164)");
    }

    @Test
    void catalogQueryCarriesTheReadOnlyTransaction() throws Exception {
        Method load = ParquetExportCatalogQuery.class.getMethod("load",
                UUID.class, LocalDateTime.class, UUID.class, String.class,
                ParquetExportFileService.FileType.class, String.class, int.class);
        Transactional tx = load.getAnnotation(Transactional.class);
        assertNotNull(tx, "the catalog page must still load in one read-only transaction");
        assertTrue(tx.readOnly());
    }

    @Test
    void existenceProbeRunsWithNoAmbientTransaction() {
        CatalogRow row = new CatalogRow(SITE_ID, "shop.example.com", "orders",
                ParquetExportFileService.FileType.DELTA, 1L, 2L, null, T1,
                S3CheckpointStorage.deltaKey(SITE_ID, "orders", 1L, 2L),
                null, null, null);
        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(),
                isNull(), isNull(), eq(3))).thenReturn(List.of(row));

        AtomicBoolean probeSawTransaction = new AtomicBoolean(true);
        when(checkpointStorage.deltaPresence(eq(SITE_ID), eq("orders"), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    probeSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return S3CheckpointStorage.ObjectPresence.PRESENT;
                });

        service.listFiles(ACCOUNT_ID, EPOCH, null, null,
                ParquetExportFileService.FileType.DELTA, null, 2);

        assertFalse(probeSawTransaction.get(),
                "the S3 existence probe must run after the catalog transaction has closed");
    }
}
