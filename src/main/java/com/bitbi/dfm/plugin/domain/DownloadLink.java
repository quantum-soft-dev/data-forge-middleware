package com.bitbi.dfm.plugin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

/**
 * A registered one-time download link for a Parquet file (028-parquet-export-plugin).
 * <p>
 * The row is the single-use gate that S3 presigned URLs cannot provide: consumption is an
 * atomic {@code UPDATE ... WHERE consumed_at IS NULL} (see {@code DownloadLinkRepository#consume}),
 * so exactly one concurrent request wins the redirect. The entity itself is write-once —
 * consumption never happens through entity mutation.
 * </p>
 */
@Entity
@Table(name = "download_links")
@Getter
@NoArgsConstructor
public class DownloadLink {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, updatable = false, length = 64)
    private String token;

    @Column(name = "account_plugin_id", nullable = false, updatable = false)
    private Long accountPluginId;

    @Column(name = "s3_key", nullable = false, updatable = false, length = 1000)
    private String s3Key;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Register a new one-time link with a fresh cryptographically random token.
     *
     * @param accountPluginId owning parquet-export activation
     * @param s3Key           S3 object key snapshot at listing time
     * @param fileName        suggested download filename
     * @param ttl             validity window before first use
     */
    public static DownloadLink register(Long accountPluginId, String s3Key, String fileName, Duration ttl) {
        DownloadLink link = new DownloadLink();
        link.id = UUID.randomUUID();
        link.token = generateToken();
        link.accountPluginId = accountPluginId;
        link.s3Key = s3Key;
        link.fileName = fileName;
        link.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        link.expiresAt = link.createdAt.plus(ttl);
        return link;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }
}
