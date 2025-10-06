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

    int countActiveBatchesByAccountId(UUID accountId);

    void deleteById(UUID id);
}
