package com.bitbi.dfm.error.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for ErrorLog entity.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface ErrorLogRepository {

    ErrorLog save(ErrorLog errorLog);

    Optional<ErrorLog> findById(UUID errorId);

    List<ErrorLog> findByBatchId(UUID batchId);

    List<ErrorLog> findBySiteId(UUID siteId);

    Page<ErrorLog> findBySiteId(UUID siteId, Pageable pageable);

    Page<ErrorLog> findByTypeAndOccurredAtBetween(String type, LocalDateTime start,
                                                    LocalDateTime end, Pageable pageable);

    List<ErrorLog> exportByFilters(UUID siteId, String type, LocalDateTime start, LocalDateTime end);

    long countByBatchId(UUID batchId);

    long countBySiteId(UUID siteId);

    long countBySiteIds(List<UUID> siteIds);

    long count();
}
