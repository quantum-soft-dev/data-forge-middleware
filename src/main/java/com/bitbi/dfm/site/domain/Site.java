package com.bitbi.dfm.site.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Site aggregate root representing a data source location.
 * <p>
 * A site belongs to an account and owns authentication credentials for uploads.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "sites")
@Getter
@NoArgsConstructor
public class Site {

    /**
     * Length of composite domain prefix (UUID + underscore).
     * UUID is 36 characters + underscore (1 character) = 37 characters total.
     */
    private static final int COMPOSITE_DOMAIN_PREFIX_LENGTH = 37;
    private static final int DEFAULT_RETENTION_DAYS = 45;
    private static final int MAX_RETENTION_DAYS = 3650; // 10 years

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "domain", nullable = false, unique = true, length = 255)
    private String domain;

    @Column(name = "site_name", nullable = false, length = 255)
    private String siteName;

    @Column(name = "client_secret_hash", length = 60)
    private String clientSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "site_type", nullable = false, length = 20)
    private SiteType siteType;

    /** Supported client ingestion API. Feature 032 makes V2 the sole valid value. */
    @Enumerated(EnumType.STRING)
    @Column(name = "client_api_version", nullable = false, length = 2)
    private ClientApiVersion clientApiVersion = ClientApiVersion.V2;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "retention_days", nullable = false)
    private Integer retentionDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Site(UUID id, UUID accountId, String domain, String siteName, String clientSecretHash,
                   SiteType siteType, String displayName, Boolean isActive, Integer retentionDays,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.domain = domain;
        this.siteName = siteName;
        this.clientSecretHash = clientSecretHash;
        this.siteType = siteType;
        this.displayName = displayName;
        this.isActive = isActive;
        this.retentionDays = retentionDays;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.clientApiVersion = ClientApiVersion.V2;
    }

    /**
     * Create a new site with composite domain (legacy) and siteName.
     *
     * @param accountId        account identifier
     * @param domain           composite domain (accountId_siteName)
     * @param siteName         clean site name (Unicode allowed)
     * @param displayName      human-readable display name
     * @param clientSecretHash bcrypt-hashed client secret (nullable for Auth V2)
     * @param siteType         site type (DBF or POSTGRES_CDC); defaults to DBF if null
     * @return new Site entity
     */
    public static Site create(UUID accountId, String domain, String siteName, String displayName,
                              String clientSecretHash, SiteType siteType) {
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(siteName, "SiteName cannot be null");
        Objects.requireNonNull(displayName, "DisplayName cannot be null");

        if (domain.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be blank");
        }
        if (siteName.isBlank()) {
            throw new IllegalArgumentException("SiteName cannot be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("DisplayName cannot be blank");
        }

        // Normalize and validate site name
        String normalizedSiteName = validateAndNormalizeSiteName(siteName);

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SiteType resolvedType = siteType != null ? siteType : SiteType.DBF;

        return new Site(id, accountId, domain.toLowerCase().trim(), normalizedSiteName, clientSecretHash,
                resolvedType, displayName.trim(), true, DEFAULT_RETENTION_DAYS, now, now);
    }

    /**
     * Create a new site with composite domain (legacy) and siteName. Site type defaults to DBF.
     *
     * @param accountId        account identifier
     * @param domain           composite domain (accountId_siteName)
     * @param siteName         clean site name (Unicode allowed)
     * @param displayName      human-readable display name
     * @param clientSecretHash bcrypt-hashed client secret (nullable for Auth V2)
     * @return new Site entity
     */
    public static Site create(UUID accountId, String domain, String siteName, String displayName, String clientSecretHash) {
        return create(accountId, domain, siteName, displayName, clientSecretHash, SiteType.DBF);
    }

    /**
     * @deprecated Use {@link #create(UUID, String, String, String, String)} instead.
     */
    @Deprecated
    public static Site create(UUID accountId, String domain, String displayName, String clientSecretHash) {
        // Extract siteName from composite domain for backward compatibility
        String siteName = domain;
        if (domain.length() > COMPOSITE_DOMAIN_PREFIX_LENGTH) {
            siteName = domain.substring(COMPOSITE_DOMAIN_PREFIX_LENGTH);
        }
        return create(accountId, domain, siteName, displayName, clientSecretHash);
    }

    /**
     * Create site with generated bcrypt hash for testing purposes.
     * WARNING: Only use in test code.
     *
     * @param accountId   account identifier
     * @param domain      site domain
     * @param displayName site display name
     * @return created site with auto-generated bcrypt hash
     */
    public static Site createForTesting(UUID accountId, String domain, String displayName) {
        String[] secretPair = SiteCredentials.generateWithHash(domain);
        String hashedSecret = secretPair[1]; // Use only hash, discard plaintext
        String siteName = domain;
        if (domain.length() > COMPOSITE_DOMAIN_PREFIX_LENGTH) {
            siteName = domain.substring(COMPOSITE_DOMAIN_PREFIX_LENGTH);
        }
        return create(accountId, domain, siteName, displayName, hashedSecret);
    }

    /**
     * Validate and normalize a site name.
     * <ul>
     *   <li>Applies Unicode NFC normalization</li>
     *   <li>Rejects null bytes and path traversal sequences</li>
     *   <li>Validates UTF-8 byte length fits VARCHAR(255)</li>
     * </ul>
     */
    private static String validateAndNormalizeSiteName(String siteName) {
        // NFC normalization (é vs e + combining accent → canonical form)
        String normalized = Normalizer.normalize(siteName.trim(), Normalizer.Form.NFC);

        // Reject null bytes
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Site name must not contain null bytes");
        }

        // Reject path traversal
        if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Site name must not contain path traversal characters");
        }

        // Validate UTF-8 byte length (VARCHAR(255) = 255 bytes)
        if (normalized.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Site name exceeds 255 bytes");
        }

        return normalized;
    }

    public void updateDisplayName(String newDisplayName) {
        Objects.requireNonNull(newDisplayName, "DisplayName cannot be null");
        if (newDisplayName.isBlank()) {
            throw new IllegalArgumentException("DisplayName cannot be blank");
        }
        this.displayName = newDisplayName.trim();
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void updateRetentionDays(int retentionDays) {
        if (retentionDays <= 0 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("Retention days must be between 1 and " + MAX_RETENTION_DAYS);
        }
        this.retentionDays = retentionDays;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void deactivate() {
        if (!this.isActive) {
            throw new IllegalStateException("Site is already deactivated");
        }
        this.isActive = false;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void activate() {
        if (this.isActive) {
            throw new IllegalStateException("Site is already active");
        }
        this.isActive = true;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Update client secret hash.
     * <p>
     * Used when reconnecting to an existing site via Device Flow.
     * Previous secrets become invalid immediately.
     * </p>
     *
     * @param newClientSecretHash bcrypt-hashed new secret
     */
    public void updateClientSecretHash(String newClientSecretHash) {
        Objects.requireNonNull(newClientSecretHash, "ClientSecretHash cannot be null");
        if (newClientSecretHash.isBlank()) {
            throw new IllegalArgumentException("ClientSecretHash cannot be blank");
        }
        this.clientSecretHash = newClientSecretHash;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Extract display domain from composite domain (accountId_domain format).
     * Domain is stored as "uuid_domain" per FR-019.
     *
     * @return display domain without accountId prefix
     * @deprecated Use {@link #getSiteName()} instead
     */
    @Deprecated
    public String getDisplayDomain() {
        if (siteName != null) {
            return siteName;
        }
        // Fallback for legacy data
        if (domain == null || domain.length() <= COMPOSITE_DOMAIN_PREFIX_LENGTH) {
            return domain;
        }
        return domain.substring(COMPOSITE_DOMAIN_PREFIX_LENGTH);
    }

    public boolean canAuthenticate() {
        return this.isActive;
    }

    public boolean verifySecret(String providedSecret) {
        if (clientSecretHash == null) {
            return false; // Auth V2 sites have no client secret
        }
        return new SiteCredentials(domain, clientSecretHash).verifySecret(providedSecret);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (updatedAt == null) updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Site site)) return false;
        return Objects.equals(id, site.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
