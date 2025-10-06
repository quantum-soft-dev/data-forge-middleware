package com.bitbi.dfm.site.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Site aggregate.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SiteRepository {

    Optional<Site> findById(UUID id);

    Optional<Site> findByDomain(String domain);

    List<Site> findByAccountId(UUID accountId);

    Site save(Site site);

    boolean existsByDomain(String domain);

    void deleteById(UUID id);
}
