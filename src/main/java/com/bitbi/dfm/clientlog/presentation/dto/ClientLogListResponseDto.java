package com.bitbi.dfm.clientlog.presentation.dto;

import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response DTO for client diagnostic logs.
 *
 * Feature: 021-unified-upload-api
 * User Story: US5 - Admin Views and Downloads Client Logs
 */
public record ClientLogListResponseDto(
        List<ClientLogResponseDto> content,
        int page,
        int size,
        long totalElements
) {
    public static ClientLogListResponseDto fromPage(Page<ClientDiagnosticLog> page) {
        List<ClientLogResponseDto> content = page.getContent().stream()
                .map(ClientLogResponseDto::fromEntity)
                .toList();

        return new ClientLogListResponseDto(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }
}
