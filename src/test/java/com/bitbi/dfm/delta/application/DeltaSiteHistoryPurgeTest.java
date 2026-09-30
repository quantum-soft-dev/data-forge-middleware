package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.shared.storage.S3PrefixLister.S3PrefixWalk;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The object phase of a site hard delete (issue #367): it walks the deleted site's whole object
 * history, so it must never run with a transaction — and its connection — held open.
 */
@DisplayName("DeltaSiteHistoryPurge — objects of a deleted site")
class DeltaSiteHistoryPurgeTest {

    private static final UUID SITE_ID = UUID.randomUUID();

    private final S3FileStorageService s3FileStorageService = mock(S3FileStorageService.class);
    private final S3CheckpointStorage checkpointStorage = mock(S3CheckpointStorage.class);
    private final S3ChangelogSegmentStorage segmentStorage = mock(S3ChangelogSegmentStorage.class);

    private final DeltaSiteHistoryPurge purge = new DeltaSiteHistoryPurge(
            mock(BatchRepository.class), mock(UploadedFileRepository.class),
            mock(PluginSqlGenerationRepository.class), mock(PluginDeltaBaselineRepository.class),
            mock(AccountPluginRepository.class), mock(ChangelogSegmentRepository.class),
            mock(CheckpointRepository.class), mock(BatchParquetArtifactRepository.class),
            mock(ErrorLogRepository.class), s3FileStorageService, checkpointStorage, segmentStorage);

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("refuses to run inside a transaction and touches no object")
    void refusesInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> purge.deleteObjectsOfDeletedSite(SITE_ID, List.of("a/key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(s3FileStorageService, checkpointStorage, segmentStorage);
    }

    @Test
    @DisplayName("outside a transaction deletes the exact keys and walks the three site prefixes")
    void deletesExactKeysAndWalksThePrefixes() {
        when(s3FileStorageService.deleteObjects(anyList())).thenReturn(new DeleteObjectsResult(1, List.of()));
        when(segmentStorage.walkPrefix(any(), any())).thenReturn(new S3PrefixWalk(0, false));
        when(checkpointStorage.walkPrefix(any(), any())).thenReturn(new S3PrefixWalk(0, false));

        purge.deleteObjectsOfDeletedSite(SITE_ID, List.of("a/key"));

        verify(s3FileStorageService).deleteObjects(List.of("a/key"));
        verify(segmentStorage).walkPrefix(eq(S3ChangelogSegmentStorage.segmentPrefix(SITE_ID)), any());
        verify(checkpointStorage).walkPrefix(eq(S3CheckpointStorage.checkpointPrefix(SITE_ID)), any());
        verify(checkpointStorage).walkPrefix(eq(S3CheckpointStorage.egressPrefix(SITE_ID)), any());
    }
}
