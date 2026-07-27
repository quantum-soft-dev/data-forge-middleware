package com.bitbi.dfm.plugin.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for one-time download links (028-parquet-export-plugin).
 */
public interface DownloadLinkRepository {

    DownloadLink save(DownloadLink link);

    Optional<DownloadLink> findByToken(String token);

    /**
     * Atomically consume a link: succeeds only while the link is unconsumed, unexpired, and its
     * owning activation is still active. Exactly one concurrent caller can win.
     *
     * @return affected rows: {@code 1} = consumed by this call, {@code 0} = lost/ineligible
     */
    int consume(String token, LocalDateTime now);

    /**
     * Delete consumed or expired links created before the cutoff. {@code now} is passed in
     * (UTC application time) so the expiry comparison uses the same clock as {@link #consume},
     * not the database session's timezone.
     *
     * @return number of rows deleted
     */
    int purge(LocalDateTime cutoff, LocalDateTime now);
}
