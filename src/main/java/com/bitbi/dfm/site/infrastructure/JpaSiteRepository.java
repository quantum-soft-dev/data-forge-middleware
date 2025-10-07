package com.bitbi.dfm.site.infrastructure;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of SiteRepository.
 * <p>
 * Uses @EntityGraph to prevent N+1 queries when fetching sites with accounts.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaSiteRepository extends JpaRepository<Site, UUID>, SiteRepository {

    /**
     * Find site by domain (case-insensitive).
     *
     * @param domain site domain
     * @return Optional containing site if found
     */
    @Query("SELECT s FROM Site s WHERE LOWER(s.domain) = LOWER(:domain)")
    Optional<Site> findByDomain(String domain);

    /**
     * Find all sites for given account.
     *
     * @param accountId account identifier
     * @return list of sites
     */
    @Query("SELECT s FROM Site s WHERE s.accountId = :accountId ORDER BY s.createdAt DESC")
    List<Site> findByAccountId(UUID accountId);

    /**
     * Find all active sites for given account.
     *
     * @param accountId account identifier
     * @return list of active sites
     */
    @Query("SELECT s FROM Site s WHERE s.accountId = :accountId AND s.isActive = true ORDER BY s.createdAt DESC")
    List<Site> findActiveByAccountId(UUID accountId);

    /**
     * Count sites by account ID.
     *
     * @param accountId account identifier
     * @return number of sites
     */
    @Query("SELECT COUNT(s) FROM Site s WHERE s.accountId = :accountId")
    long countByAccountId(UUID accountId);

    /**
     * Check if site exists with given domain (case-insensitive).
     *
     * @param domain domain to check
     * @return true if site exists
     */
    @Query("SELECT COUNT(s) > 0 FROM Site s WHERE LOWER(s.domain) = LOWER(:domain)")
    boolean existsByDomain(String domain);
}
