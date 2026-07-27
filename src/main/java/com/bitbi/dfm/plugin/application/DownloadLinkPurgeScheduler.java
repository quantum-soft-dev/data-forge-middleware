package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.DownloadLinkRepository;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Purges consumed and expired one-time download links after the retention window
 * (028-parquet-export-plugin) so {@code download_links} stays bounded.
 */
@Component
public class DownloadLinkPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(DownloadLinkPurgeScheduler.class);

    private final DownloadLinkRepository downloadLinkRepository;
    private final ParquetExportProperties properties;

    public DownloadLinkPurgeScheduler(DownloadLinkRepository downloadLinkRepository,
                                      ParquetExportProperties properties) {
        this.downloadLinkRepository = downloadLinkRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${plugin.parquet-export.purge-interval-ms:3600000}")
    public void purgeStaleLinks() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(properties.getPurgeRetentionDays());
        int deleted = downloadLinkRepository.purge(cutoff, now);
        if (deleted > 0) {
            log.info("Purged {} stale download links (cutoff={})", deleted, cutoff);
        }
    }
}
