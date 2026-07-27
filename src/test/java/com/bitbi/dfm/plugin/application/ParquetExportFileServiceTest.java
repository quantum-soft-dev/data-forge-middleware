package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileListing;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao.CatalogRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ParquetExportFileService} (028-parquet-export-plugin, T009 + review:
 * keyset cursor over (producedAt, s3Key), SQL-side pagination via {@link ParquetExportCatalogDao}).
 */
@ExtendWith(MockitoExtension.class)
class ParquetExportFileServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 7, 20, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 7, 21, 10, 0);

    @Mock
    private ParquetExportCatalogDao catalogDao;

    @Mock
    private S3CheckpointStorage checkpointStorage;

    private ParquetExportFileService service;

    @BeforeEach
    void setUp() {
        service = new ParquetExportFileService(catalogDao, checkpointStorage);
    }

    private CatalogRow deltaRow(String table, long firstSeq, long lastSeq, LocalDateTime producedAt) {
        return new CatalogRow(SITE_ID, "shop.example.com", table, FileType.DELTA,
                firstSeq, lastSeq, null, producedAt,
                S3CheckpointStorage.deltaKey(SITE_ID, table, firstSeq, lastSeq));
    }

    private CatalogRow checkpointRow(String table, long seq, LocalDateTime producedAt) {
        return new CatalogRow(SITE_ID, "shop.example.com", table, FileType.CHECKPOINT,
                null, null, seq, producedAt, "checkpoints/" + SITE_ID + "/" + table + "/seq=" + seq
                + "/snapshot.parquet");
    }

    @Test
    @DisplayName("Should map delta and checkpoint rows to items with filenames")
    void shouldMapRowsToItems() {
        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(), isNull(), isNull(), eq(51)))
                .thenReturn(List.of(deltaRow("orders", 100, 250, T1)));
        when(catalogDao.findCheckpointFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(), isNull(), isNull(), eq(51)))
                .thenReturn(List.of(checkpointRow("orders", 250, T2)));
        when(checkpointStorage.deltaExists(SITE_ID, "orders", 100, 250)).thenReturn(true);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 50);

        assertEquals(2, listing.files().size());
        ParquetFileItem delta = listing.files().get(0);
        assertEquals(FileType.DELTA, delta.type());
        assertEquals("orders_seq100-250.parquet", delta.fileName());
        assertEquals(100L, delta.firstSeq());
        assertNull(delta.seq());
        ParquetFileItem checkpoint = listing.files().get(1);
        assertEquals(FileType.CHECKPOINT, checkpoint.type());
        assertEquals("orders_seq250.parquet", checkpoint.fileName());
        assertEquals(250L, checkpoint.seq());
        assertFalse(listing.hasMore());
        assertNull(listing.nextCursor());
    }

    @Test
    @DisplayName("Should not lose files sharing one producedAt across a page boundary (review P0)")
    void shouldWalkEqualProducedAtViaCursor() {
        // One segment fans out to 3 tables — all with the same producedAt. Page size 2.
        CatalogRow a = deltaRow("a_table", 1, 2, T1);
        CatalogRow b = deltaRow("b_table", 1, 2, T1);
        CatalogRow c = deltaRow("c_table", 1, 2, T1);
        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(a, b, c));
        when(catalogDao.findCheckpointFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(checkpointStorage.deltaExists(eq(SITE_ID), any(), anyLong(), anyLong())).thenReturn(true);

        FileListing pageOne = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 2);

        assertEquals(2, pageOne.files().size());
        assertTrue(pageOne.hasMore());
        assertNotNull(pageOne.nextCursor());

        // The cursor must resume strictly after (T1, b_table-key) — same producedAt, key tiebreak.
        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(),
                eq(T1), eq(b.s3Key()), eq(3)))
                .thenReturn(List.of(c));

        FileListing pageTwo = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, pageOne.nextCursor(), 2);

        assertEquals(1, pageTwo.files().size());
        assertEquals("c_table", pageTwo.files().get(0).table());
        assertFalse(pageTwo.hasMore());
    }

    @Test
    @DisplayName("Should advance cursor over candidates dropped by the existence probe")
    void shouldAdvanceCursorOverDroppedCandidates() {
        CatalogRow present = deltaRow("orders", 1, 2, T1);
        CatalogRow missing = deltaRow("poison", 1, 2, T1.plusMinutes(1));
        CatalogRow later = deltaRow("orders", 3, 4, T2);
        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(present, missing, later));
        when(catalogDao.findCheckpointFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(checkpointStorage.deltaExists(SITE_ID, "orders", 1, 2)).thenReturn(true);
        when(checkpointStorage.deltaExists(SITE_ID, "poison", 1, 2)).thenReturn(false);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 2);

        // Page shorter than size while hasMore stays true — cursor points at the dropped candidate.
        assertEquals(1, listing.files().size());
        assertEquals("orders", listing.files().get(0).table());
        assertTrue(listing.hasMore());
        assertNotNull(listing.nextCursor());

        when(catalogDao.findDeltaFiles(eq(ACCOUNT_ID), eq(EPOCH), isNull(), isNull(),
                eq(missing.producedAt()), eq(missing.s3Key()), eq(3)))
                .thenReturn(List.of(later));
        when(checkpointStorage.deltaExists(SITE_ID, "orders", 3, 4)).thenReturn(true);

        FileListing next = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, listing.nextCursor(), 2);
        assertEquals(1, next.files().size());
        assertEquals(3L, next.files().get(0).firstSeq());
    }

    @Test
    @DisplayName("Should merge delta and checkpoint sources by (producedAt, s3Key)")
    void shouldMergeSourcesInOrder() {
        when(catalogDao.findDeltaFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(deltaRow("orders", 1, 2, T1), deltaRow("orders", 3, 4, T2.plusHours(2))));
        when(catalogDao.findCheckpointFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(checkpointRow("orders", 2, T2)));
        when(checkpointStorage.deltaExists(eq(SITE_ID), any(), anyLong(), anyLong())).thenReturn(true);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 50);

        assertEquals(List.of(T1, T2, T2.plusHours(2)),
                listing.files().stream().map(ParquetFileItem::producedAt).toList());
    }

    @Test
    @DisplayName("Should query only the requested source for a type filter")
    void shouldQueryOnlyRequestedType() {
        when(catalogDao.findDeltaFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, FileType.DELTA, null, 50);

        assertTrue(listing.files().isEmpty());
        verify(catalogDao, never()).findCheckpointFiles(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Should pass site and table filters through to the DAO")
    void shouldPassFiltersToDao() {
        when(catalogDao.findCheckpointFiles(eq(ACCOUNT_ID), eq(T1), eq(SITE_ID), eq("orders"),
                isNull(), isNull(), eq(11)))
                .thenReturn(List.of());

        service.listFiles(ACCOUNT_ID, T1, SITE_ID, "orders", FileType.CHECKPOINT, null, 10);

        verify(catalogDao).findCheckpointFiles(eq(ACCOUNT_ID), eq(T1), eq(SITE_ID), eq("orders"),
                isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("Should reject invalid cursor values")
    void shouldRejectInvalidCursor() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, "not-a-cursor!!!", 10));
    }

    @Test
    @DisplayName("Should reject page size outside 1..100")
    void shouldRejectInvalidPageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 101));
        assertThrows(IllegalArgumentException.class,
                () -> service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 0));
        verifyNoInteractions(catalogDao);
    }

    @Test
    @DisplayName("Should request at most size+1 rows per source (bounded window)")
    void shouldRequestBoundedWindow() {
        when(catalogDao.findDeltaFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(catalogDao.findCheckpointFiles(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, null, 50);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(catalogDao).findDeltaFiles(any(), any(), any(), any(), any(), any(), limitCaptor.capture());
        assertEquals(51, limitCaptor.getValue());
    }
}
