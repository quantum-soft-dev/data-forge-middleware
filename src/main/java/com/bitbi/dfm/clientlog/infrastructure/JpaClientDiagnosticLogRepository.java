package com.bitbi.dfm.clientlog.infrastructure;

import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA implementation of ClientDiagnosticLogRepository.
 *
 * Feature: 021-unified-upload-api
 * User Story: US4, US5 - Client Diagnostic Logs
 */
@Repository
public interface JpaClientDiagnosticLogRepository
        extends JpaRepository<ClientDiagnosticLog, UUID>, ClientDiagnosticLogRepository {

    @Override
    long countBySiteIdAndUploadedAtAfter(UUID siteId, LocalDateTime since);

    @Override
    Page<ClientDiagnosticLog> findBySiteIdOrderByUploadedAtDesc(UUID siteId, Pageable pageable);

    @Override
    List<ClientDiagnosticLog> findByExpiresAtBefore(LocalDateTime before);
}
