package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.plugin.application.BitBiV2FileService.Snapshot;
import com.bitbi.dfm.plugin.application.BitBiV2FileService.SnapshotTable;
import com.bitbi.dfm.plugin.application.BitBiV2FileService.TableStatus;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /snapshot} and {@code /files/{fileId}} of the Bit BI v2 surface (issue #387), over the
 * real {@link BitBiV2QueryService} and stubbed repositories and storage.
 *
 * <p>The build window is the case worth reading: a build saves each table's row at the new seq
 * before it moves the pointer, and a build that ends part way leaves the rows like that until the
 * next night. The listing must stay at the pointer throughout, so a row that has moved on is
 * answered from the object the build left at the pointer.</p>
 */
@DisplayName("BitBiV2FileService")
class BitBiV2FileServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("0199bab2-aaaa-7000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("0199bab2-bbbb-7000-8000-000000000002");
    private static final UUID SITE = UUID.fromString("0199bab2-cccc-7000-8000-000000000003");
    private static final long EPOCH = 3L;
    private static final long POINTER = 5_020_646L;

    private SiteRepository siteRepository;
    private DeltaSyncStateService syncStateService;
    private CheckpointRepository checkpointRepository;
    private S3CheckpointStorage checkpointStorage;
    private S3PresignedUrlService presignedUrlService;
    private BitBiV2FileService service;

    @BeforeEach
    void setUp() {
        siteRepository = mock(SiteRepository.class);
        syncStateService = mock(DeltaSyncStateService.class);
        checkpointRepository = mock(CheckpointRepository.class);
        checkpointStorage = mock(S3CheckpointStorage.class);
        presignedUrlService = mock(S3PresignedUrlService.class);
        BitBiV2QueryService queryService = new BitBiV2QueryService(siteRepository, syncStateService,
                new SiteSchemaService(mock(SiteSchemaRepository.class), JsonMapper.builder().build()),
                checkpointRepository);
        service = new BitBiV2FileService(queryService, checkpointStorage, presignedUrlService);

        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE);
        when(siteRepository.findByIdAndAccountId(SITE, ACCOUNT)).thenReturn(Optional.of(site));
        when(siteRepository.findByIdAndAccountId(SITE, OTHER_ACCOUNT)).thenReturn(Optional.empty());
        pointerAt(POINTER);
    }

    private void pointerAt(long pointer) {
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(pointer + 100, pointer, 1, false, false, 1L, EPOCH));
    }

    private static String keyAt(String table, long seq) {
        return S3CheckpointStorage.snapshotKey(SITE, table, seq);
    }

    private static Checkpoint row(String table, long seq, boolean materialized) {
        Checkpoint checkpoint = Checkpoint.create(SITE, table, seq, 10L);
        if (materialized) {
            checkpoint.attachParquet(keyAt(table, seq));
        }
        return checkpoint;
    }

    private void rows(Checkpoint... rows) {
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(rows));
        for (Checkpoint row : rows) {
            when(checkpointRepository.findBySiteIdAndTableName(SITE, row.getTableName())).thenReturn(Optional.of(row));
        }
    }

    private static String fileIdAt(String table, long seq) {
        return new BitBiFileId.Checkpoint(SITE, EPOCH, seq, table).encode();
    }

    @Nested
    @DisplayName("GET /snapshot")
    class SnapshotListing {

        @Test
        @DisplayName("Every table at the pointer is ready, sorted, with a fileId pinned to that seq; no S3 call")
        void allTablesAtThePointer() {
            rows(row("orders", POINTER, true), row("nsftrns", POINTER, true));

            Snapshot snapshot = service.snapshot(ACCOUNT, SITE);

            assertThat(snapshot.siteId()).isEqualTo(SITE);
            assertThat(snapshot.epoch()).isEqualTo(EPOCH);
            assertThat(snapshot.checkpointSeq()).isEqualTo(POINTER);
            assertThat(snapshot.tables()).containsExactly(
                    new SnapshotTable("nsftrns", TableStatus.READY, fileIdAt("nsftrns", POINTER)),
                    new SnapshotTable("orders", TableStatus.READY, fileIdAt("orders", POINTER)));
            verify(checkpointStorage, never()).presence(anyString());
        }

        @Test
        @DisplayName("In the build window a row already at the next seq is listed from the object at the pointer")
        void aRowAheadOfThePointerIsListedAtThePointer() {
            rows(row("nsftrns", POINTER + 900, true), row("orders", POINTER, true));
            when(checkpointStorage.presence(keyAt("nsftrns", POINTER))).thenReturn(ObjectPresence.PRESENT);

            Snapshot snapshot = service.snapshot(ACCOUNT, SITE);

            assertThat(snapshot.checkpointSeq()).isEqualTo(POINTER);
            assertThat(snapshot.tables()).containsExactly(
                    new SnapshotTable("nsftrns", TableStatus.READY, fileIdAt("nsftrns", POINTER)),
                    new SnapshotTable("orders", TableStatus.READY, fileIdAt("orders", POINTER)));
            verify(checkpointStorage, never()).presence(keyAt("nsftrns", POINTER + 900));
        }

        @Test
        @DisplayName("A row ahead of the pointer with nothing at the pointer is missing — never the newer file")
        void aRowAheadWithNoObjectAtThePointerIsMissing() {
            rows(row("tmp_x", POINTER + 900, true));
            when(checkpointStorage.presence(keyAt("tmp_x", POINTER))).thenReturn(ObjectPresence.ABSENT);

            assertThat(service.snapshot(ACCOUNT, SITE).tables())
                    .containsExactly(new SnapshotTable("tmp_x", TableStatus.MISSING, null));
        }

        @Test
        @DisplayName("A table with no snapshot (given up, #149) is listed as missing, not skipped")
        void aDetachedTableIsMissing() {
            rows(row("nsfcard", POINTER, false), row("orders", POINTER, true));

            assertThat(service.snapshot(ACCOUNT, SITE).tables()).containsExactly(
                    new SnapshotTable("nsfcard", TableStatus.MISSING, null),
                    new SnapshotTable("orders", TableStatus.READY, fileIdAt("orders", POINTER)));
        }

        @Test
        @DisplayName("A row below the pointer describes an older state and is missing")
        void aRowBehindThePointerIsMissing() {
            rows(row("old", POINTER - 1, true));

            assertThat(service.snapshot(ACCOUNT, SITE).tables())
                    .containsExactly(new SnapshotTable("old", TableStatus.MISSING, null));
            verify(checkpointStorage, never()).presence(anyString());
        }

        @Test
        @DisplayName("S3 refusing to say whether the object at the pointer exists is a storage failure, not a guess")
        void undecidablePresenceFails() {
            rows(row("nsftrns", POINTER + 900, true));
            when(checkpointStorage.presence(keyAt("nsftrns", POINTER))).thenReturn(ObjectPresence.UNKNOWN);

            assertThatThrownBy(() -> service.snapshot(ACCOUNT, SITE))
                    .isInstanceOf(CheckpointStorageException.class);
        }

        @Test
        @DisplayName("No checkpoint yet: pointer 0 and no tables, whatever a first build is writing")
        void noCheckpointYet() {
            pointerAt(0L);
            rows(row("nsftrns", 12L, true));

            Snapshot snapshot = service.snapshot(ACCOUNT, SITE);

            assertThat(snapshot.checkpointSeq()).isZero();
            assertThat(snapshot.tables()).isEmpty();
        }

        @Test
        @DisplayName("Another account's site is SiteNotFoundException, like an absent one")
        void foreignSite() {
            assertThatThrownBy(() -> service.snapshot(OTHER_ACCOUNT, SITE))
                    .isInstanceOf(SiteService.SiteNotFoundException.class)
                    .hasMessage("Site not found: " + SITE);
            verify(checkpointRepository, never()).findBySiteId(any());
        }
    }

    @Nested
    @DisplayName("GET /files/{fileId}")
    class FileRedirect {

        @BeforeEach
        void presigns() {
            when(presignedUrlService.generatePresignedUrl(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(inv -> new S3PresignedUrlService.PresignedUrlResult(
                            "https://storage.example/" + inv.getArgument(0), Instant.now()));
        }

        @Test
        @DisplayName("A listed file resolves to a short-lived presigned URL of exactly that object")
        void resolvesTheListedObject() {
            rows(row("orders", POINTER, true));
            when(checkpointStorage.presence(keyAt("orders", POINTER))).thenReturn(ObjectPresence.PRESENT);

            String url = service.presignedUrl(ACCOUNT, fileIdAt("orders", POINTER));

            assertThat(url).isEqualTo("https://storage.example/" + keyAt("orders", POINTER));
            verify(presignedUrlService).generatePresignedUrl(eq(keyAt("orders", POINTER)),
                    eq("orders_seq" + POINTER + ".parquet"), eq(BitBiV2FileService.PRESIGN_TTL));
            assertThat(BitBiV2FileService.PRESIGN_TTL).isLessThanOrEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("After the next build the old id still resolves to the old object, not the row's new key")
        void staysPinnedAfterTheNextBuild() {
            rows(row("orders", POINTER + 900, true));
            pointerAt(POINTER + 900);
            when(checkpointStorage.presence(keyAt("orders", POINTER))).thenReturn(ObjectPresence.PRESENT);

            assertThat(service.presignedUrl(ACCOUNT, fileIdAt("orders", POINTER)))
                    .isEqualTo("https://storage.example/" + keyAt("orders", POINTER));
        }

        @Test
        @DisplayName("An object retention or the orphan sweep removed is 404")
        void removedObjectIsNotFound() {
            rows(row("orders", POINTER + 900, true));
            when(checkpointStorage.presence(keyAt("orders", POINTER))).thenReturn(ObjectPresence.ABSENT);

            assertNotFound(ACCOUNT, fileIdAt("orders", POINTER));
        }

        @Test
        @DisplayName("Another account's file is 404 with the message of an absent one, and S3 is not asked")
        void foreignFileIsNotFound() {
            rows(row("orders", POINTER, true));

            assertNotFound(OTHER_ACCOUNT, fileIdAt("orders", POINTER));
            verify(checkpointStorage, never()).presence(anyString());
        }

        @Test
        @DisplayName("An id of a discarded history (another epoch) is 404 even when an object sits at that key")
        void anotherEpochIsNotFound() {
            rows(row("orders", POINTER, true));
            when(checkpointStorage.presence(anyString())).thenReturn(ObjectPresence.PRESENT);

            assertNotFound(ACCOUNT, new BitBiFileId.Checkpoint(SITE, EPOCH - 1, POINTER, "orders").encode());
        }

        @Test
        @DisplayName("A table the site has no checkpoint row for is 404")
        void unknownTableIsNotFound() {
            rows(row("orders", POINTER, true));
            when(checkpointRepository.findBySiteIdAndTableName(SITE, "nope")).thenReturn(Optional.empty());

            assertNotFound(ACCOUNT, fileIdAt("nope", POINTER));
            verify(checkpointStorage, never()).presence(anyString());
        }

        @Test
        @DisplayName("A string that is not a fileId is 404, like an absent file")
        void garbageIsNotFound() {
            assertNotFound(ACCOUNT, "definitely-not-an-id");
        }

        @Test
        @DisplayName("S3 refusing to say is a storage failure, not a 404")
        void undecidablePresenceFails() {
            rows(row("orders", POINTER, true));
            when(checkpointStorage.presence(keyAt("orders", POINTER))).thenReturn(ObjectPresence.UNKNOWN);

            assertThatThrownBy(() -> service.presignedUrl(ACCOUNT, fileIdAt("orders", POINTER)))
                    .isInstanceOf(CheckpointStorageException.class);
        }

        private void assertNotFound(UUID accountId, String fileId) {
            assertThatThrownBy(() -> service.presignedUrl(accountId, fileId))
                    .isInstanceOf(BitBiV2FileService.FileNotFoundException.class)
                    .hasMessage("File not found: " + fileId);
            verify(presignedUrlService, never()).generatePresignedUrl(anyString(), anyString(), any(Duration.class));
        }
    }
}
