package com.bitbi.dfm.batch.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Batch aggregate.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface BatchRepository {

    Optional<Batch> findById(UUID id);

    Optional<Batch> findActiveBySiteId(UUID siteId);

    List<Batch> findExpiredBatches(LocalDateTime cutoffTime);

    Page<Batch> findBySiteIdAndStatus(UUID siteId, BatchStatus status, Pageable pageable);

    Batch save(Batch batch);

    long countByAccountId(UUID accountId);

    long countBySiteId(UUID siteId);

    long countActiveBatches();

    int countActiveBatchesByAccountId(UUID accountId);

    int countActiveBatchesByAccountIdWithLock(UUID accountId);

    long count();

    void deleteById(UUID id);

    Page<Batch> findBySiteId(UUID siteId, Pageable pageable);

    Page<Batch> findByStatus(BatchStatus status, Pageable pageable);

    Page<Batch> findAll(Pageable pageable);

    boolean existsById(UUID id);
}
