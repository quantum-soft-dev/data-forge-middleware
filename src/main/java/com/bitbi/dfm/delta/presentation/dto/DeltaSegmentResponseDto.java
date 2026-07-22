package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.domain.ChangelogSegment;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Response DTO for one changelog segment (feature 023 — Delta Sync UI, B6; admin surface).
 *
 * <p>Deliberately excludes the S3 key and content hash — the UI shows sequence ranges, sizes and
 * modes, never raw storage locations.</p>
 *
 * @param firstSeq    sequence range start
 * @param lastSeq     sequence range end
 * @param recordCount number of change records in the segment
 * @param mode        session mode: DELTA, CONTINUOUS or FULL_SNAPSHOT
 * @param createdAt   when the segment was committed
 */
public record DeltaSegmentResponseDto(
        long firstSeq,
        long lastSeq,
        long recordCount,
        String mode,
        Instant createdAt
) {

    /**
     * Convert the ChangelogSegment entity to its REST projection.
     *
     * @param segment the segment entity
     * @return response DTO
     */
    public static DeltaSegmentResponseDto fromEntity(ChangelogSegment segment) {
        return new DeltaSegmentResponseDto(
                segment.getFirstSeq(),
                segment.getLastSeq(),
                segment.getRecordCount(),
                segment.getMode(),
                segment.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
