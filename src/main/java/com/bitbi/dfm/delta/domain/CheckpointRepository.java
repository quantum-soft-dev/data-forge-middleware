package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Checkpoint} persistence (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface CheckpointRepository {

    Checkpoint save(Checkpoint checkpoint);

    Optional<Checkpoint> findBySiteIdAndTableName(UUID siteId, String tableName);

    List<Checkpoint> findBySiteId(UUID siteId);
}
