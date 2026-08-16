package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of {@link CheckpointRepository}.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaCheckpointRepository extends JpaRepository<Checkpoint, UUID>, CheckpointRepository {

    @Override
    @Query("SELECT c FROM Checkpoint c WHERE c.siteId = :siteId AND c.tableName = :tableName")
    Optional<Checkpoint> findBySiteIdAndTableName(UUID siteId, String tableName);

    @Override
    @Query("SELECT c FROM Checkpoint c WHERE c.siteId = :siteId")
    List<Checkpoint> findBySiteId(UUID siteId);

    @Override
    @Query("SELECT DISTINCT c.siteId FROM Checkpoint c "
            + "WHERE c.s3KeyParquet IS NULL AND c.materializeAttempts < :maxAttempts")
    List<UUID> findSiteIdsWithUnmaterializedCheckpoints(int maxAttempts);

    @Override
    @Query("SELECT COUNT(c) FROM Checkpoint c "
            + "WHERE c.s3KeyParquet IS NULL AND c.materializeAttempts >= :maxAttempts")
    long countGivenUpMaterializing(int maxAttempts);

    @Override
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM Checkpoint c WHERE c.siteId = :siteId")
    int deleteBySiteId(UUID siteId);
}
