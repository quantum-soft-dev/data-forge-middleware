package com.bitbi.dfm.clientlog.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;


/**
 * Repository interface for ClientDiagnosticLog aggregate.
 *
 * Feature: 021-unified-upload-api
 * User Story: US4, US5 - Client Diagnostic Logs
 */
public interface ClientDiagnosticLogRepository {

    ClientDiagnosticLog save(ClientDiagnosticLog log);

    Optional<ClientDiagnosticLog> findById(UUID id);

    /**
     * Count logs uploaded by a site since a given timestamp.
     * Used for daily rate limiting.
     */
    long countBySiteIdAndUploadedAtAfter(UUID siteId, LocalDateTime since);

    /**
     * Find logs for a site, ordered by upload time (newest first).
     */
    Page<ClientDiagnosticLog> findBySiteIdOrderByUploadedAtDesc(UUID siteId, Pageable pageable);

    /**
     * Find expired logs for retention cleanup (batched to avoid OOM).
     */
    Page<ClientDiagnosticLog> findByExpiresAtBefore(LocalDateTime before, Pageable pageable);

    void delete(ClientDiagnosticLog log);
}
