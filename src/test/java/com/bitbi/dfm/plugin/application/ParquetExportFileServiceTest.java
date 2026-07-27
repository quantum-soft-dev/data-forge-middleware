package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileListing;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ParquetExportFileService} (028-parquet-export-plugin, T009).
 */
@ExtendWith(MockitoExtension.class)
class ParquetExportFileServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 7, 20, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 7, 21, 10, 0);

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private ChangelogSegmentRepository segmentRepository;

    @Mock
    private CheckpointRepository checkpointRepository;

    @Mock
    private S3CheckpointStorage checkpointStorage;

    private ParquetExportFileService service;

    @BeforeEach
    void setUp() {
        service = new ParquetExportFileService(siteRepository, segmentRepository,
                checkpointRepository, checkpointStorage);
    }

    private Site site(UUID id, String domain) {
        Site site = mock(Site.class);
        lenient().when(site.getId()).thenReturn(id);
        lenient().when(site.getDomain()).thenReturn(domain);
        return site;
    }

    private ChangelogSegment segment(UUID siteId, long firstSeq, long lastSeq,
                                     LocalDateTime egressAt, Map<String, com.bitbi.dfm.delta.domain.TableChangeStats> stats) {
        ChangelogSegment segment = mock(ChangelogSegment.class);
        lenient().when(segment.getSiteId()).thenReturn(siteId);
        lenient().when(segment.getFirstSeq()).thenReturn(firstSeq);
        lenient().when(segment.getLastSeq()).thenReturn(lastSeq);
        lenient().when(segment.getEgressAt()).thenReturn(egressAt);
        lenient().when(segment.getStats()).thenReturn(stats);
        return segment;
    }

    private Checkpoint checkpoint(UUID siteId, String table, long seq, String s3KeyParquet,
                                  LocalDateTime updatedAt) {
        Checkpoint checkpoint = mock(Checkpoint.class);
        lenient().when(checkpoint.getSiteId()).thenReturn(siteId);
        lenient().when(checkpoint.getTableName()).thenReturn(table);
        lenient().when(checkpoint.getSeq()).thenReturn(seq);
        lenient().when(checkpoint.getS3KeyParquet()).thenReturn(s3KeyParquet);
        lenient().when(checkpoint.getUpdatedAt()).thenReturn(updatedAt);
        return checkpoint;
    }

    private Map<String, com.bitbi.dfm.delta.domain.TableChangeStats> statsFor(String... tables) {
        java.util.LinkedHashMap<String, com.bitbi.dfm.delta.domain.TableChangeStats> map =
                new java.util.LinkedHashMap<>();
        for (String table : tables) {
            map.put(table, mock(com.bitbi.dfm.delta.domain.TableChangeStats.class));
        }
        return map;
    }

    @Test
    @DisplayName("Should list delta files per table with derived key and filename")
    void shouldListDeltaFiles() {
        Site testSite = site(SITE_ID, "shop.example.com");
        ChangelogSegment testSegment = segment(SITE_ID, 100, 250, T1, statsFor("orders", "items"));
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of(testSegment));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of());
        when(checkpointStorage.deltaExists(eq(SITE_ID), any(), eq(100L), eq(250L))).thenReturn(true);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 50);

        assertEquals(2, listing.files().size());
        ParquetFileItem ordersItem = listing.files().stream()
                .filter(f -> f.table().equals("orders")).findFirst().orElseThrow();
        assertEquals(FileType.DELTA, ordersItem.type());
        assertEquals("shop.example.com", ordersItem.siteDomain());
        assertEquals(100L, ordersItem.firstSeq());
        assertEquals(250L, ordersItem.lastSeq());
        assertNull(ordersItem.seq());
        assertEquals(T1, ordersItem.producedAt());
        assertEquals("orders_seq100-250.parquet", ordersItem.fileName());
        assertEquals(S3CheckpointStorage.deltaKey(SITE_ID, "orders", 100, 250), ordersItem.s3Key());
        assertFalse(listing.hasMore());
    }

    @Test
    @DisplayName("Should drop delta tables whose Parquet does not exist")
    void shouldDropMissingDeltaParquet() {
        Site testSite = site(SITE_ID, "shop.example.com");
        ChangelogSegment testSegment = segment(SITE_ID, 100, 250, T1, statsFor("orders", "poison"));
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of(testSegment));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of());
        when(checkpointStorage.deltaExists(SITE_ID, "orders", 100, 250)).thenReturn(true);
        when(checkpointStorage.deltaExists(SITE_ID, "poison", 100, 250)).thenReturn(false);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 50);

        assertEquals(1, listing.files().size());
        assertEquals("orders", listing.files().get(0).table());
    }

    @Test
    @DisplayName("Should list checkpoint files with parquet key only")
    void shouldListCheckpointFiles() {
        Site testSite = site(SITE_ID, "shop.example.com");
        Checkpoint withParquet = checkpoint(SITE_ID, "orders", 250, "checkpoints/x/orders/seq=250/snapshot.parquet", T2);
        Checkpoint withoutParquet = checkpoint(SITE_ID, "no-parquet", 100, null, T2);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of());
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of(withParquet, withoutParquet));

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 50);

        assertEquals(1, listing.files().size());
        ParquetFileItem item = listing.files().get(0);
        assertEquals(FileType.CHECKPOINT, item.type());
        assertEquals(250L, item.seq());
        assertNull(item.firstSeq());
        assertEquals(T2, item.producedAt());
        assertEquals("orders_seq250.parquet", item.fileName());
        assertEquals("checkpoints/x/orders/seq=250/snapshot.parquet", item.s3Key());
    }

    @Test
    @DisplayName("Should filter checkpoints by since strictly greater")
    void shouldFilterCheckpointsBySince() {
        Site testSite = site(SITE_ID, "shop.example.com");
        Checkpoint atSince = checkpoint(SITE_ID, "orders", 250, "k1", T2); // == since → excluded
        Checkpoint afterSince = checkpoint(SITE_ID, "items", 300, "k2", T2.plusMinutes(1));
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, T2)).thenReturn(List.of());
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of(atSince, afterSince));

        FileListing listing = service.listFiles(ACCOUNT_ID, T2, null, null, null, 0, 50);

        assertEquals(1, listing.files().size());
        assertEquals("items", listing.files().get(0).table());
    }

    @Test
    @DisplayName("Should scope to account sites and ignore foreign siteId filter")
    void shouldScopeToAccountSites() {
        Site testSite = site(SITE_ID, "shop.example.com");
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, UUID.randomUUID(), null, null, 0, 50);

        assertTrue(listing.files().isEmpty());
        verifyNoInteractions(segmentRepository, checkpointRepository, checkpointStorage);
    }

    @Test
    @DisplayName("Should filter by siteId, table and type composably")
    void shouldComposeFilters() {
        UUID otherSiteId = UUID.randomUUID();
        Site ownSite = site(SITE_ID, "shop.example.com");
        Site otherSite = site(otherSiteId, "other.example.com");
        ChangelogSegment testSegment = segment(SITE_ID, 1, 2, T1, statsFor("orders", "items"));
        Checkpoint testCheckpoint = checkpoint(SITE_ID, "orders", 2, "k", T2);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(ownSite, otherSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of(testSegment));
        lenient().when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of(testCheckpoint));
        when(checkpointStorage.deltaExists(SITE_ID, "orders", 1, 2)).thenReturn(true);

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, SITE_ID, "orders", FileType.DELTA, 0, 50);

        assertEquals(1, listing.files().size());
        ParquetFileItem item = listing.files().get(0);
        assertEquals("orders", item.table());
        assertEquals(FileType.DELTA, item.type());
        // only the requested site was queried
        verify(segmentRepository, never()).findEgressedSince(eq(otherSiteId), any());
    }

    @Test
    @DisplayName("Should order by producedAt and paginate deterministically")
    void shouldPaginate() {
        Site testSite = site(SITE_ID, "shop.example.com");
        ChangelogSegment newer = segment(SITE_ID, 3, 4, T2, statsFor("orders"));
        ChangelogSegment older = segment(SITE_ID, 1, 2, T1, statsFor("orders"));
        Checkpoint testCheckpoint = checkpoint(SITE_ID, "orders", 4, "k", T2.plusHours(1));
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of(newer, older));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of(testCheckpoint));
        when(checkpointStorage.deltaExists(eq(SITE_ID), any(), anyLong(), anyLong())).thenReturn(true);

        FileListing pageZero = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 2);
        FileListing pageOne = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 1, 2);

        assertEquals(2, pageZero.files().size());
        assertEquals(T1, pageZero.files().get(0).producedAt());
        assertEquals(T2, pageZero.files().get(1).producedAt());
        assertTrue(pageZero.hasMore());
        assertEquals(1, pageOne.files().size());
        assertEquals(FileType.CHECKPOINT, pageOne.files().get(0).type());
        assertFalse(pageOne.hasMore());
    }

    @Test
    @DisplayName("Should skip segments without stats")
    void shouldSkipSegmentsWithoutStats() {
        Site testSite = site(SITE_ID, "shop.example.com");
        ChangelogSegment statlessSegment = segment(SITE_ID, 1, 2, T1, null);
        when(siteRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(testSite));
        when(segmentRepository.findEgressedSince(SITE_ID, EPOCH)).thenReturn(List.of(statlessSegment));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of());

        FileListing listing = service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 50);

        assertTrue(listing.files().isEmpty());
        verifyNoInteractions(checkpointStorage);
    }

    @Test
    @DisplayName("Should cap page size at 100")
    void shouldCapPageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listFiles(ACCOUNT_ID, EPOCH, null, null, null, 0, 101));
    }
}
