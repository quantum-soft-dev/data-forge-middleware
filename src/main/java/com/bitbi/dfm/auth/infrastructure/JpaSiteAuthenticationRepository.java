package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.domain.SiteAuthenticationRepository;
import com.bitbi.dfm.auth.domain.SiteAuthenticationStatus;
import com.bitbi.dfm.site.domain.Site;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of {@link SiteAuthenticationRepository}.
 * <p>
 * Site and Account have no JPA association, so the join is expressed explicitly against the
 * fully qualified Account entity (same pattern as {@code JpaErrorLogRepository}). Returning a
 * constructor expression keeps the whole check to a single SELECT on the request hot path.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaSiteAuthenticationRepository
        extends org.springframework.data.repository.Repository<Site, UUID>, SiteAuthenticationRepository {

    @Override
    @Query("""
            SELECT new com.bitbi.dfm.auth.domain.SiteAuthenticationStatus(
                       s.id, s.accountId, s.isActive, a.isActive)
            FROM Site s
            JOIN com.bitbi.dfm.account.domain.Account a ON s.accountId = a.id
            WHERE s.id = :siteId
            """)
    Optional<SiteAuthenticationStatus> findAuthenticationStatus(UUID siteId);
}
