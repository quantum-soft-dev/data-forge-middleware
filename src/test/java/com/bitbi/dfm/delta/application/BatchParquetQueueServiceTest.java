package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchParquetQueueServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID ARTIFACT_ID = UUID.randomUUID();

    private BatchParquetArtifactRepository repository;
    private AdminActionLogRepository auditRepository;
    private BatchParquetQueueService service;

    @BeforeEach
    void setUp() {
        repository = mock(BatchParquetArtifactRepository.class);
        auditRepository = mock(AdminActionLogRepository.class);
        service = new BatchParquetQueueService(repository, auditRepository, 1800);
    }

    @Test
    void listsOnlyTheRequestedSiteAndBatch() {
        List<BatchParquetArtifact> artifacts = List.of(mock(BatchParquetArtifact.class));
        when(repository.findBySiteIdAndBatchIdOrderByTableName(SITE_ID, BATCH_ID))
                .thenReturn(artifacts);

        assertEquals(artifacts, service.list(SITE_ID, BATCH_ID));
    }

    @Test
    void requeuesAnAbandonedArtifactUnderLockAndAuditsTheCommittedReset() {
        BatchParquetArtifact artifact = artifact(BatchParquetArtifactStatus.ABANDONED,
                LocalDateTime.now(ZoneOffset.UTC));
        Site site = site();
        when(repository.findForUpdate(ARTIFACT_ID, SITE_ID, BATCH_ID))
                .thenReturn(Optional.of(artifact));
        when(repository.save(artifact)).thenReturn(artifact);

        assertEquals(artifact, service.requeue(site, BATCH_ID, ARTIFACT_ID,
                "192.0.2.4", "queue-console"));

        verify(artifact).requeue();
        verify(repository).save(artifact);
        ArgumentCaptor<AdminActionLog> audit = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(auditRepository).save(audit.capture());
        assertEquals(AdminActionType.BATCH_PARQUET_REQUEUE, audit.getValue().getActionType());
        assertEquals(ACCOUNT_ID, audit.getValue().getTargetAccountId());
        assertEquals(SITE_ID, audit.getValue().getTargetSiteId());
        assertEquals("192.0.2.4", audit.getValue().getIpAddress());
        assertEquals("queue-console", audit.getValue().getUserAgent());
        assertEquals(ARTIFACT_ID.toString(), audit.getValue().getDetails().get("artifactId"));
        assertEquals(BATCH_ID.toString(), audit.getValue().getDetails().get("batchId"));
        assertEquals("orders", audit.getValue().getDetails().get("tableName"));
        assertEquals("ABANDONED", audit.getValue().getDetails().get("previousStatus"));
    }

    @Test
    void requeuesOnlyABuildingClaimWhoseLeaseExpired() {
        Site site = site();
        BatchParquetArtifact expired = artifact(BatchParquetArtifactStatus.BUILDING,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(repository.findForUpdate(ARTIFACT_ID, SITE_ID, BATCH_ID))
                .thenReturn(Optional.of(expired));
        when(repository.save(expired)).thenReturn(expired);

        service.requeue(site, BATCH_ID, ARTIFACT_ID, null, null);

        verify(expired).requeue();

        BatchParquetArtifact live = artifact(BatchParquetArtifactStatus.BUILDING,
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        when(repository.findForUpdate(ARTIFACT_ID, SITE_ID, BATCH_ID))
                .thenReturn(Optional.of(live));

        assertThrows(BatchParquetQueueService.ArtifactNotRequeueableException.class,
                () -> service.requeue(site, BATCH_ID, ARTIFACT_ID, null, null));
        verify(live, never()).requeue();
    }

    @Test
    void rejectsAStateThatDoesNotNeedOperatorRecovery() {
        BatchParquetArtifact failed = artifact(BatchParquetArtifactStatus.FAILED,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(repository.findForUpdate(ARTIFACT_ID, SITE_ID, BATCH_ID))
                .thenReturn(Optional.of(failed));

        assertThrows(BatchParquetQueueService.ArtifactNotRequeueableException.class,
                () -> service.requeue(site(), BATCH_ID, ARTIFACT_ID, null, null));

        verify(failed, never()).requeue();
        verify(auditRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routeMismatchIsNotFoundAndNeverAudited() {
        when(repository.findForUpdate(ARTIFACT_ID, SITE_ID, BATCH_ID))
                .thenReturn(Optional.empty());

        assertThrows(BatchParquetQueueService.ArtifactNotFoundException.class,
                () -> service.requeue(site(), BATCH_ID, ARTIFACT_ID, null, null));

        verify(auditRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static BatchParquetArtifact artifact(BatchParquetArtifactStatus status,
                                                  LocalDateTime updatedAt) {
        BatchParquetArtifact artifact = mock(BatchParquetArtifact.class);
        when(artifact.getId()).thenReturn(ARTIFACT_ID);
        when(artifact.getBatchId()).thenReturn(BATCH_ID);
        when(artifact.getSiteId()).thenReturn(SITE_ID);
        when(artifact.getTableName()).thenReturn("orders");
        when(artifact.getStatus()).thenReturn(status);
        when(artifact.getUpdatedAt()).thenReturn(updatedAt);
        return artifact;
    }

    private static Site site() {
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE_ID);
        when(site.getAccountId()).thenReturn(ACCOUNT_ID);
        return site;
    }
}
