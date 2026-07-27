package com.bitbi.dfm.plugin.infrastructure;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Parquet Export plugin (028).
 *
 * <pre>
 * plugin:
 *   parquet-export:
 *     link-ttl-seconds: 3600      # one-time link validity before first use
 *     presign-ttl-seconds: 60     # S3 presigned URL validity after consume
 *     purge-retention-days: 7     # keep consumed/expired download_links rows this long
 *     purge-interval-ms: 3600000  # purge sweep cadence
 *     base-url: ""                # absolute prefix for download URLs; empty = derive from request
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "plugin.parquet-export")
@Validated
public class ParquetExportProperties {

    @Positive
    private long linkTtlSeconds = 3600;

    @Positive
    private long presignTtlSeconds = 60;

    @Positive
    private int purgeRetentionDays = 7;

    @Positive
    private long purgeIntervalMs = 3_600_000;

    private String baseUrl = "";

    public long getLinkTtlSeconds() {
        return linkTtlSeconds;
    }

    public void setLinkTtlSeconds(long linkTtlSeconds) {
        this.linkTtlSeconds = linkTtlSeconds;
    }

    public long getPresignTtlSeconds() {
        return presignTtlSeconds;
    }

    public void setPresignTtlSeconds(long presignTtlSeconds) {
        this.presignTtlSeconds = presignTtlSeconds;
    }

    public int getPurgeRetentionDays() {
        return purgeRetentionDays;
    }

    public void setPurgeRetentionDays(int purgeRetentionDays) {
        this.purgeRetentionDays = purgeRetentionDays;
    }

    public long getPurgeIntervalMs() {
        return purgeIntervalMs;
    }

    public void setPurgeIntervalMs(long purgeIntervalMs) {
        this.purgeIntervalMs = purgeIntervalMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
