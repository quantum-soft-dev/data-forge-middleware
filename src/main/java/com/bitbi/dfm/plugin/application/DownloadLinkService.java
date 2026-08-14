package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.DownloadLink;
import com.bitbi.dfm.plugin.domain.DownloadLinkRepository;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registers and consumes one-time download links (028-parquet-export-plugin).
 * <p>
 * Single-use is enforced by {@link DownloadLinkRepository#consume}: an atomic UPDATE that only
 * one concurrent request can win. The S3 presigned URL (short TTL) is minted strictly after a
 * won consume — losing requests never see a URL.
 * </p>
 */
@Service
public class DownloadLinkService {

    private static final Logger log = LoggerFactory.getLogger(DownloadLinkService.class);

    private final DownloadLinkRepository downloadLinkRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final S3PresignedUrlService presignedUrlService;
    private final PluginAuditService pluginAuditService;
    private final ParquetExportProperties properties;
    private final MeterRegistry meterRegistry;

    public DownloadLinkService(DownloadLinkRepository downloadLinkRepository,
                               AccountPluginRepository accountPluginRepository,
                               S3PresignedUrlService presignedUrlService,
                               PluginAuditService pluginAuditService,
                               ParquetExportProperties properties,
                               MeterRegistry meterRegistry) {
        this.downloadLinkRepository = downloadLinkRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.presignedUrlService = presignedUrlService;
        this.pluginAuditService = pluginAuditService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Register a one-time link for every listed file (links are never deduplicated across
     * listings — TTL and the purge job bound the growth).
     *
     * @return saved links, in the same order as {@code files}
     */
    @Transactional
    public List<DownloadLink> registerLinks(Long accountPluginId, List<ParquetFileItem> files) {
        Duration ttl = Duration.ofSeconds(properties.getLinkTtlSeconds());
        List<DownloadLink> links = new ArrayList<>(files.size());
        for (ParquetFileItem file : files) {
            if (file.s3Key() == null) {
                links.add(null);
                continue;
            }
            links.add(downloadLinkRepository.save(
                    DownloadLink.register(accountPluginId, file.s3Key(), file.fileName(), ttl)));
        }
        return links;
    }

    /**
     * Atomically consume a link and mint the short-lived presigned URL.
     *
     * @return the S3 presigned URL to redirect to
     * @throws LinkGoneException     consumed, expired, or its activation is inactive (410)
     * @throws LinkNotFoundException unknown token (404)
     */
    @Transactional
    public String consume(String token) {
        int updated = downloadLinkRepository.consume(token, LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            DownloadLink existing = downloadLinkRepository.findByToken(token).orElse(null);
            if (existing == null) {
                // No audit row for unknown tokens: the route is anonymous, so a garbage-token
                // flood would otherwise write-amplify into the partitioned audit table.
                // Aggregated metric only; alerts belong on this counter or ingress rate limits.
                meterRegistry.counter("plugin.parquet.export.download.rejected", "reason", "unknown")
                        .increment();
                log.warn("Unknown download link token rejected");
                throw new LinkNotFoundException();
            }
            UUID accountId = resolveAccountId(existing.getAccountPluginId());
            String reason = existing.getConsumedAt() != null ? "consumed"
                    : existing.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC)) ? "expired"
                    : "inactive";
            meterRegistry.counter("plugin.parquet.export.download.rejected", "reason", reason).increment();
            pluginAuditService.logLinkRejected(ParquetExportCredentialsService.PLUGIN_ID, accountId, reason);
            throw new LinkGoneException(reason);
        }

        DownloadLink link = downloadLinkRepository.findByToken(token)
                .orElseThrow(LinkNotFoundException::new); // just consumed — cannot be absent
        S3PresignedUrlService.PresignedUrlResult result = presignedUrlService.generatePresignedUrl(
                link.getS3Key(), link.getFileName(),
                Duration.ofSeconds(properties.getPresignTtlSeconds()));

        pluginAuditService.logLinkConsumed(ParquetExportCredentialsService.PLUGIN_ID,
                resolveAccountId(link.getAccountPluginId()), link.getFileName(), link.getS3Key());
        log.info("One-time link consumed: file={}, accountPluginId={}", link.getFileName(),
                link.getAccountPluginId());
        return result.url();
    }

    private UUID resolveAccountId(Long accountPluginId) {
        return accountPluginRepository.findById(accountPluginId)
                .map(com.bitbi.dfm.plugin.domain.AccountPlugin::getAccountId)
                .orElse(null);
    }

    /** Link exists but is consumed, expired, or its activation is inactive → HTTP 410. */
    public static class LinkGoneException extends RuntimeException {
        public LinkGoneException(String reason) {
            super("Download link is no longer valid: " + reason);
        }
    }

    /** Unknown (or purged) token → HTTP 404. */
    public static class LinkNotFoundException extends RuntimeException {
        public LinkNotFoundException() {
            super("Download link not found");
        }
    }
}
