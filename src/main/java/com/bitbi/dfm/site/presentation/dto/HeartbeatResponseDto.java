package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.site.domain.ForceFullUploadReason;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteSchema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for the heartbeat endpoint.
 * <p>
 * Returns site status, server directives, schema info,
 * last completed batch info, and server time.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US2 - Heartbeat Pre-Batch Sync Check
 */
public record HeartbeatResponseDto(
        UUID siteId,
        String siteStatus,
        DirectivesDto directives,
        SchemaDto schema,
        LastCompletedBatchDto lastCompletedBatch,
        Instant serverTime
) {

    public record DirectivesDto(
            boolean forceFullUpload,
            String forceFullUploadReason,
            boolean requestLogs,
            String requestLogsMessage
    ) {
        public static DirectivesDto fromSite(Site site) {
            ForceFullUploadReason reason = site.getForceFullUploadReason();
            return new DirectivesDto(
                    site.getForceFullUpload(),
                    reason != null ? reason.name() : null,
                    site.getRequestLogs(),
                    site.getRequestLogsMessage()
            );
        }
    }

    public record SchemaDto(
            Integer version,
            Instant updatedAt
    ) {
        public static SchemaDto fromSchema(SiteSchema schema) {
            return new SchemaDto(
                    schema.getSchemaVersion(),
                    schema.getUpdatedAt().toInstant(ZoneOffset.UTC)
            );
        }
    }

    public record LastCompletedBatchDto(
            UUID batchId,
            Instant completedAt,
            String status
    ) {
        public static LastCompletedBatchDto fromBatch(Batch batch) {
            return new LastCompletedBatchDto(
                    batch.getId(),
                    batch.getCompletedAt() != null
                            ? batch.getCompletedAt().toInstant(ZoneOffset.UTC)
                            : null,
                    batch.getStatus().name()
            );
        }
    }

    public static HeartbeatResponseDto build(Site site, SiteSchema schema, Batch lastCompletedBatch) {
        return new HeartbeatResponseDto(
                site.getId(),
                site.getIsActive() ? "ACTIVE" : "INACTIVE",
                DirectivesDto.fromSite(site),
                schema != null ? SchemaDto.fromSchema(schema) : null,
                lastCompletedBatch != null ? LastCompletedBatchDto.fromBatch(lastCompletedBatch) : null,
                Instant.now()
        );
    }
}
