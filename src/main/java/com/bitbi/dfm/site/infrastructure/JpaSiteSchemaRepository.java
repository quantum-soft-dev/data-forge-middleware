package com.bitbi.dfm.site.infrastructure;

import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of SiteSchemaRepository.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaSiteSchemaRepository extends JpaRepository<SiteSchema, UUID>, SiteSchemaRepository {

    @Query("SELECT s FROM SiteSchema s WHERE s.siteId = :siteId")
    Optional<SiteSchema> findBySiteId(UUID siteId);

    @Query("SELECT COUNT(s) > 0 FROM SiteSchema s WHERE s.siteId = :siteId")
    boolean existsBySiteId(UUID siteId);

    @Modifying
    @Query("DELETE FROM SiteSchema s WHERE s.siteId = :siteId")
    void deleteBySiteId(UUID siteId);
}
