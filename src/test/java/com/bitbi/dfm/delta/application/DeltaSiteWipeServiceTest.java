package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the site history wipe (035 — issue #89).
 *
 * <p>The wipe destroys everything the server knows about a site, so the interesting properties are
 * the ones that keep it from destroying more, less, or at the wrong moment: the FK-dictated order,
 * S3 keys collected before their rows disappear, the two 409 guards, and the objects leaving the
 * bucket only once the rows are durably gone.</p>
 */
@DisplayName("DeltaSiteWipeService")
class DeltaSiteWipeServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final int BATCH_TIMEOUT_MINUTES = 60;

    private final SiteSyncStateRepository syncStateRepository = mock(SiteSyncStateRepository.class);
    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final UploadedFileRepository uploadedFileRepository = mock(UploadedFileRepository.class);
    private final PluginSqlGenerationRepository sqlGenerationRepository = mock(PluginSqlGenerationRepository.class);
    private final PluginDeltaBaselineRepository baselineRepository = mock(PluginDeltaBaselineRepository.class);
    private final AccountPluginRepository accountPluginRepository = mock(AccountPluginRepository.class);
    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final BatchParquetArtifactRepository artifactRepository = mock(BatchParquetArtifactRepository.class);
    private final ErrorLogRepository errorLogRepository = mock(ErrorLogRepository.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final S3FileStorageService s3FileStorageService = mock(S3FileStorageService.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private final AdminActionLogRepository adminActionLogRepository = mock(AdminActionLogRepository.class);

    private DeltaSiteWipeService service;

    @BeforeEach
    void setUp() {
        service = new DeltaSiteWipeService(directTransactionTemplate(), syncStateRepository, batchRepository,
                uploadedFileRepository, sqlGenerationRepository, baselineRepository, accountPluginRepository,
                segmentRepository, checkpointRepository, artifactRepository, errorLogRepository, siteSchemaService,
                s3FileStorageService, checkpointStorage, adminActionLogRepository, BATCH_TIMEOUT_MINUTES);

        when(syncStateRepository.findBySiteIdForUpdate(SITE_ID))
                .thenReturn(Optional.of(SiteSyncState.initial(SITE_ID)));
        when(batchRepository.findActiveBySiteId(SITE_ID)).thenReturn(Optional.empty());
        when(uploadedFileRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(List.of());
        when(sqlGenerationRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(List.of());
        when(segmentRepository.findAllS3KeysBySiteId(SITE_ID)).thenReturn(List.of());
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(List.of());
        when(artifactRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(List.of());
        when(s3FileStorageService.deleteObjects(anyList())).thenReturn(new DeleteObjectsResult(0, List.of()));
        when(checkpointStorage.listAllKeys(any())).thenReturn(List.of());
        when(batchRepository.countBySiteId(SITE_ID)).thenReturn(0L);
    }

    /** Runs the callback inline: this test asserts on what the block does, not on Spring's plumbing. */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private static Site site() {
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);
        return site;
    }

    private static UploadedFileRepository.FileKeySize uploadedFile(String key, Long size) {
        UploadedFileRepository.FileKeySize projection = mock(UploadedFileRepository.FileKeySize.class);
        when(projection.getS3Key()).thenReturn(key);
        when(projection.getFileSize()).thenReturn(size);
        return projection;
    }

    private static PluginSqlGenerationRepository.S3KeySize sqlGeneration(String key, Long size) {
        PluginSqlGenerationRepository.S3KeySize projection = mock(PluginSqlGenerationRepository.S3KeySize.class);
        when(projection.getS3Key()).thenReturn(key);
        when(projection.getFileSizeBytes()).thenReturn(size);
        return projection;
    }

    private static Checkpoint checkpoint(String csvKey, String parquetKey) {
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getS3KeyCsv()).thenReturn(csvKey);
        when(checkpoint.getS3KeyParquet()).thenReturn(parquetKey);
        return checkpoint;
    }

    @Test
    @DisplayName("deletes in FK order: plugin SQL, baselines, segments, checkpoints, error logs, "
            + "baseline detach, batches, schema")
    void shouldDeleteInForeignKeyOrder() {
        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        InOrder order = inOrder(sqlGenerationRepository, baselineRepository, segmentRepository,
                checkpointRepository, artifactRepository, errorLogRepository, accountPluginRepository,
                batchRepository, siteSchemaService);
        order.verify(sqlGenerationRepository).deleteBySiteId(SITE_ID);
        order.verify(baselineRepository).deleteBySiteId(SITE_ID);
        // Segments before batches: changelog_segments.batch_id has no cascade.
        order.verify(segmentRepository).deleteBySiteId(SITE_ID);
        order.verify(checkpointRepository).deleteBySiteId(SITE_ID);
        order.verify(artifactRepository).deleteBySiteId(SITE_ID);
        order.verify(errorLogRepository).deleteBySiteId(SITE_ID);
        // Detach before batches: the baseline_batch_id FK is ON DELETE RESTRICT.
        order.verify(accountPluginRepository).detachBaselineBatchesOfSite(SITE_ID);
        order.verify(batchRepository).deleteBySiteId(SITE_ID);
        order.verify(siteSchemaService).deleteSchema(SITE_ID);
    }

    @Test
    @DisplayName("collects every S3 key before the rows that name them are deleted")
    void shouldCollectKeysBeforeDeleting() {
        // Built before the stubbing they feed: a mock created inside a when(...) argument is what
        // Mockito reports as UnfinishedStubbingException.
        List<UploadedFileRepository.FileKeySize> files = List.of(uploadedFile("uploads/a.csv", 100L));
        List<PluginSqlGenerationRepository.S3KeySize> sql = List.of(sqlGeneration("plugins/bit-bi/x.sql", 20L));
        List<Checkpoint> checkpoints = List.of(checkpoint("cp/t.csv.gz", "cp/t.parquet"));
        when(uploadedFileRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(files);
        when(sqlGenerationRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(sql);
        when(segmentRepository.findAllS3KeysBySiteId(SITE_ID))
                .thenReturn(List.of("segments/1.pb.gz", "segments/2.pb.gz"));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(checkpoints);
        when(artifactRepository.findS3KeysBySiteId(SITE_ID))
                .thenReturn(List.of("egress/site/batches/batch/orders.parquet"));

        service.wipe(site(), DeltaSiteWipeService.Initiator.OWNER);

        InOrder order = inOrder(uploadedFileRepository, sqlGenerationRepository, segmentRepository,
                checkpointRepository, artifactRepository);
        order.verify(uploadedFileRepository).findS3KeysBySiteId(SITE_ID);
        order.verify(sqlGenerationRepository).findS3KeysBySiteId(SITE_ID);
        order.verify(sqlGenerationRepository).deleteBySiteId(SITE_ID);
        order.verify(segmentRepository).findAllS3KeysBySiteId(SITE_ID);
        order.verify(segmentRepository).deleteBySiteId(SITE_ID);
        order.verify(checkpointRepository).findBySiteId(SITE_ID);
        order.verify(checkpointRepository).deleteBySiteId(SITE_ID);
        order.verify(artifactRepository).findS3KeysBySiteId(SITE_ID);
        order.verify(artifactRepository).deleteBySiteId(SITE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(s3FileStorageService).deleteObjects(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "uploads/a.csv", "plugins/bit-bi/x.sql",
                "segments/1.pb.gz", "segments/2.pb.gz",
                "cp/t.csv.gz", "cp/t.parquet",
                "egress/site/batches/batch/orders.parquet");
    }

    @Test
    @DisplayName("reports what was destroyed, including the new generation")
    void shouldReportSummary() {
        List<UploadedFileRepository.FileKeySize> files =
                List.of(uploadedFile("uploads/a.csv", 100L), uploadedFile("uploads/b.csv", 250L));
        List<PluginSqlGenerationRepository.S3KeySize> sql = List.of(sqlGeneration("plugins/bit-bi/x.sql", 20L));
        when(uploadedFileRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(files);
        when(sqlGenerationRepository.findS3KeysBySiteId(SITE_ID)).thenReturn(sql);
        when(sqlGenerationRepository.deleteBySiteId(SITE_ID)).thenReturn(1);
        when(segmentRepository.deleteBySiteId(SITE_ID)).thenReturn(45);
        when(checkpointRepository.deleteBySiteId(SITE_ID)).thenReturn(12);
        when(errorLogRepository.deleteBySiteId(SITE_ID)).thenReturn(33);
        when(batchRepository.deleteBySiteId(SITE_ID)).thenReturn(123);

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.generation()).isEqualTo(1L);
        assertThat(summary.deletedBatches()).isEqualTo(123);
        assertThat(summary.deletedSegments()).isEqualTo(45);
        assertThat(summary.deletedCheckpoints()).isEqualTo(12);
        assertThat(summary.deletedErrorLogs()).isEqualTo(33);
        assertThat(summary.deletedSqlGenerations()).isEqualTo(1);
        assertThat(summary.deletedFiles()).isEqualTo(2);
        assertThat(summary.deletedBytes()).isEqualTo(370L);
        assertThat(summary.baselineBatchDetached()).isFalse();
    }

    @Test
    @DisplayName("reports a detached plugin baseline batch")
    void shouldReportDetachedBaseline() {
        when(accountPluginRepository.detachBaselineBatchesOfSite(SITE_ID)).thenReturn(1);

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.baselineBatchDetached()).isTrue();
    }

    @Test
    @DisplayName("counts S3 delete failures instead of failing the wipe")
    void shouldCountS3Failures() {
        when(s3FileStorageService.deleteObjects(anyList()))
                .thenReturn(new DeleteObjectsResult(3, List.of("a: AccessDenied", "b: AccessDenied")));

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        // The rows are already gone — the safe direction. Orphaned objects are reported, not fatal.
        assertThat(summary.s3DeleteErrors()).isEqualTo(2);
    }

    @Test
    @DisplayName("deletes the site's egress Parquet objects, which no row names")
    void shouldDeleteEgressObjects() {
        // The wipe resets the sequence counter, so these keys — derived from seq alone — are reused
        // by the new epoch. A survivor would be served as a post-wipe batch's delta by
        // BatchParquetDownloadService whenever egress does not overwrite it.
        when(checkpointStorage.listAllKeys("egress/" + SITE_ID + "/")).thenReturn(List.of(
                "egress/" + SITE_ID + "/orders/delta/seq=1-1000.parquet",
                "egress/" + SITE_ID + "/items/delta/seq=1-1000.parquet"));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(s3FileStorageService).deleteObjects(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "egress/" + SITE_ID + "/orders/delta/seq=1-1000.parquet",
                "egress/" + SITE_ID + "/items/delta/seq=1-1000.parquet");
    }

    @Test
    @DisplayName("does not enumerate egress objects for a wipe that rolled back")
    void shouldNotTouchEgressWhenRolledBack() {
        when(batchRepository.countBySiteId(SITE_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN))
                .isInstanceOf(DeltaSiteWipeService.ConcurrentSessionException.class);

        verify(checkpointStorage, never()).listAllKeys(any());
    }

    @Test
    @DisplayName("a failed listing leaves orphans but does not fail a committed wipe")
    void shouldSurviveEgressListingFailure() {
        when(checkpointStorage.listAllKeys(any()))
                .thenThrow(new S3CheckpointStorage.CheckpointStorageException("boom", null));
        when(batchRepository.deleteBySiteId(SITE_ID)).thenReturn(7);

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        // The rows are already committed as gone; reporting a completed wipe as a 500 would be worse.
        assertThat(summary.deletedBatches()).isEqualTo(7);
        verify(s3FileStorageService).deleteObjects(anyList());
    }

    @Test
    @DisplayName("a delete phase that throws is reported, not raised: the rows are already gone")
    void shouldReportADeletePhaseThatThrows() {
        // DeleteObjects answers per-object failures inside a 200, but a client-side failure (read
        // timeout, connection reset) arrives as an exception — and this phase now issues one round
        // trip per thousand keys, so a wiped site's checkpoint prefix makes that far likelier.
        // Letting it out would report a committed wipe as a 500.
        when(segmentRepository.findAllS3KeysBySiteId(SITE_ID))
                .thenReturn(List.of("segments/1.pb.gz", "segments/2.pb.gz"));
        when(s3FileStorageService.deleteObjects(anyList())).thenThrow(new RuntimeException("reset by peer"));
        when(batchRepository.deleteBySiteId(SITE_ID)).thenReturn(7);

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.deletedBatches()).isEqualTo(7);
        // Nothing is known about how far it got, so every key handed over counts as left behind.
        assertThat(summary.s3DeleteErrors()).isEqualTo(2);
    }

    @Test
    @DisplayName("deletes the checkpoint objects of earlier builds, which no row names any more")
    void shouldDeleteSupersededCheckpointObjects() {
        // The checkpoints row is one per (site, table) and reused across builds: every build writes
        // a new seq= object and replaces the key, so only the newest is ever named by a row.
        // The reload frames under _frame/ are named by no row at all.
        List<Checkpoint> checkpoints = List.of(
                checkpoint(null, "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet"));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(checkpoints);
        when(checkpointStorage.listAllKeys("checkpoints/" + SITE_ID + "/")).thenReturn(List.of(
                "checkpoints/" + SITE_ID + "/customers/seq=10/snapshot.csv.gz",
                "checkpoints/" + SITE_ID + "/customers/seq=20/snapshot.parquet",
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet",
                "checkpoints/" + SITE_ID + "/_frame/seq=30/frame.pb.gz"));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(s3FileStorageService).deleteObjects(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "checkpoints/" + SITE_ID + "/customers/seq=10/snapshot.csv.gz",
                "checkpoints/" + SITE_ID + "/customers/seq=20/snapshot.parquet",
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet",
                "checkpoints/" + SITE_ID + "/_frame/seq=30/frame.pb.gz");
    }

    @Test
    @DisplayName("keeps the keys recorded on the checkpoint rows when the prefix listing fails")
    void shouldFallBackToRecordedCheckpointKeys() {
        // Belt and braces: the walk is the thorough half, the recorded keys are the half that
        // survives an S3 listing outage.
        List<Checkpoint> checkpoints = List.of(checkpoint(
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.csv.gz",
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet"));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(checkpoints);
        when(checkpointStorage.listAllKeys("checkpoints/" + SITE_ID + "/"))
                .thenThrow(new S3CheckpointStorage.CheckpointStorageException("boom", null));
        when(checkpointStorage.listAllKeys("egress/" + SITE_ID + "/"))
                .thenReturn(List.of("egress/" + SITE_ID + "/orders/delta/seq=1-2.parquet"));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(s3FileStorageService).deleteObjects(keys.capture());
        // One prefix failing must not cost the other one its keys.
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.csv.gz",
                "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet",
                "egress/" + SITE_ID + "/orders/delta/seq=1-2.parquet");
    }

    @Test
    @DisplayName("does not enumerate the checkpoint prefix for a wipe that rolled back")
    void shouldNotTouchCheckpointPrefixWhenRolledBack() {
        when(batchRepository.countBySiteId(SITE_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN))
                .isInstanceOf(DeltaSiteWipeService.ConcurrentSessionException.class);

        verify(checkpointStorage, never()).listAllKeys("checkpoints/" + SITE_ID + "/");
    }

    @Test
    @DisplayName("names an object once even when both a row and the prefix walk found it")
    void shouldNotDeleteTheSameKeyTwice() {
        String current = "checkpoints/" + SITE_ID + "/customers/seq=30/snapshot.parquet";
        List<Checkpoint> checkpoints = List.of(checkpoint(null, current));
        when(checkpointRepository.findBySiteId(SITE_ID)).thenReturn(checkpoints);
        when(checkpointStorage.listAllKeys("checkpoints/" + SITE_ID + "/")).thenReturn(List.of(current));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(s3FileStorageService).deleteObjects(keys.capture());
        assertThat(keys.getValue()).containsExactly(current);
    }

    @Test
    @DisplayName("bumps the generation and re-arms the rebaseline request")
    void shouldResetSyncStateForWipe() {
        SiteSyncState state = SiteSyncState.initial(SITE_ID);
        state.advanceWatermark(500L);
        when(syncStateRepository.findBySiteIdForUpdate(SITE_ID)).thenReturn(Optional.of(state));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(state.getGeneration()).isEqualTo(1L);
        assertThat(state.getLastAppliedSeq()).isZero();
        assertThat(state.isRebaselineRequested()).isTrue();
        assertThat(state.isWipePending()).isTrue();
        verify(syncStateRepository).save(state);
    }

    @Test
    @DisplayName("a never-synced site gets a sync state row so the epoch contract still holds")
    void shouldUpsertSyncStateForNeverSyncedSite() {
        when(syncStateRepository.findBySiteIdForUpdate(SITE_ID)).thenReturn(Optional.empty());

        SiteHistoryWipeSummary summary = service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        // Legacy DBF and never-connected sites are allowed: the delta steps are no-ops, but the row
        // has to exist at generation >= 1 in case the site later becomes V2.
        assertThat(summary.generation()).isEqualTo(1L);
        assertThat(summary.deletedBatches()).isZero();

        ArgumentCaptor<SiteSyncState> saved = ArgumentCaptor.forClass(SiteSyncState.class);
        verify(syncStateRepository).save(saved.capture());
        assertThat(saved.getValue().getSiteId()).isEqualTo(SITE_ID);
        assertThat(saved.getValue().getGeneration()).isEqualTo(1L);
    }

    @Test
    @DisplayName("refuses to run while a session is live")
    void shouldRefuseWhileSessionLive() {
        Batch live = mock(Batch.class);
        when(live.isExpired(BATCH_TIMEOUT_MINUTES)).thenReturn(false);
        when(batchRepository.findActiveBySiteId(SITE_ID)).thenReturn(Optional.of(live));

        assertThatThrownBy(() -> service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN))
                .isInstanceOf(DeltaSiteWipeService.SessionInProgressException.class);

        // Nothing may be destroyed: the gRPC stream holds heap-local session state on whichever pod
        // owns it, so there is no way to force-cancel it from here.
        verify(batchRepository, never()).deleteBySiteId(any());
        verify(s3FileStorageService, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("a batch abandoned past the liveness window does not block the wipe")
    void shouldIgnoreStaleInProgressBatch() {
        Batch stale = mock(Batch.class);
        when(stale.isExpired(BATCH_TIMEOUT_MINUTES)).thenReturn(true);
        when(batchRepository.findActiveBySiteId(SITE_ID)).thenReturn(Optional.of(stale));

        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN);

        verify(batchRepository).deleteBySiteId(SITE_ID);
    }

    @Test
    @DisplayName("rolls back when a batch commits concurrently")
    void shouldAbortOnConcurrentBatch() {
        // A client that reconnected while the wipe was running: its batch committed after the
        // delete, so the site would come out of the wipe with history the operator thinks is gone.
        when(batchRepository.countBySiteId(SITE_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN))
                .isInstanceOf(DeltaSiteWipeService.ConcurrentSessionException.class);

        // Rolled back rows must keep their objects: the S3 delete never runs.
        verify(s3FileStorageService, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("audits the wipe in the same transaction")
    void shouldAuditTheWipe() {
        when(batchRepository.deleteBySiteId(SITE_ID)).thenReturn(7);

        service.wipe(site(), DeltaSiteWipeService.Initiator.OWNER);

        ArgumentCaptor<AdminActionLog> entry = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(entry.capture());
        assertThat(entry.getValue().getActionType()).isEqualTo(AdminActionType.SITE_HISTORY_WIPE);
        assertThat(entry.getValue().getTargetSiteId()).isEqualTo(SITE_ID);
        assertThat(entry.getValue().getTargetAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(entry.getValue().getDetails())
                .containsEntry("initiator", "OWNER")
                .containsEntry("deletedBatches", 7)
                .containsEntry("generation", 1L)
                .containsEntry("baselineBatchDetached", false);
    }

    @Test
    @DisplayName("audits the caller's IP and user agent — the only attribution this action has")
    void shouldAuditTheRequestContext() {
        // admin_account_id is always NULL here (admins are Auth0 users with no accounts row), so the
        // IP and user agent are the only things in admin_action_logs that name the machine behind
        // the most destructive operation in the product.
        service.wipe(site(), DeltaSiteWipeService.Initiator.ADMIN, "203.0.113.7", "Mozilla/5.0");

        ArgumentCaptor<AdminActionLog> entry = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(entry.capture());
        assertThat(entry.getValue().getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(entry.getValue().getUserAgent()).isEqualTo("Mozilla/5.0");
    }
}
