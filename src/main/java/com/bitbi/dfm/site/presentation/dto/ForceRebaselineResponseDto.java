package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.domain.ForceFullUploadReason;
import com.bitbi.dfm.site.domain.Site;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for force rebaseline admin endpoint.
 *
 * Feature: 021-unified-upload-api
 * User Story: US3 - Admin Forces Rebaseline
 */
public record ForceRebaselineResponseDto(
        UUID siteId,
        boolean forceFullUpload,
        ForceFullUploadReason reason,
        String message,
        LocalDateTime setAt,
        String setBy
) {
    public static ForceRebaselineResponseDto fromEntity(Site site) {
        return new ForceRebaselineResponseDto(
                site.getId(),
                site.getForceFullUpload(),
                site.getForceFullUploadReason(),
                site.getForceFullUploadMessage(),
                site.getForceFullUploadSetAt(),
                site.getForceFullUploadSetBy()
        );
    }
}
