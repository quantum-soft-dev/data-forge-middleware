package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.DownloadLink;
import com.bitbi.dfm.plugin.domain.DownloadLinkRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of {@link DownloadLinkRepository}.
 */
@Repository
public interface JpaDownloadLinkRepository
        extends JpaRepository<DownloadLink, UUID>, DownloadLinkRepository {

    @Override
    Optional<DownloadLink> findByToken(String token);

    /**
     * Native UPDATE so the single-use transition is one atomic statement; the EXISTS guard makes
     * deactivating the plugin invalidate all of its unconsumed links without a bulk write.
     */
    @Override
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE download_links dl
               SET consumed_at = :now
             WHERE dl.token = :token
               AND dl.consumed_at IS NULL
               AND dl.expires_at > :now
               AND EXISTS (SELECT 1 FROM account_plugins ap
                            WHERE ap.id = dl.account_plugin_id AND ap.is_active = true)
            """, nativeQuery = true)
    int consume(@Param("token") String token, @Param("now") LocalDateTime now);

    @Override
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            DELETE FROM download_links dl
             WHERE (dl.consumed_at IS NOT NULL OR dl.expires_at < :now)
               AND dl.created_at < :cutoff
            """, nativeQuery = true)
    int purge(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
