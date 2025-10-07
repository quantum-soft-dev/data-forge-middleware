package com.bitbi.dfm.site.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "domain", nullable = false, unique = true, length = 255)
    private String domain;

    @Column(name = "client_secret_hash", nullable = false, length = 60)
    private String clientSecretHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Site(UUID id, UUID accountId, String domain, String clientSecretHash,
                   String displayName, Boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.domain = domain;
        this.clientSecretHash = clientSecretHash;
        this.displayName = displayName;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Site create(UUID accountId, String domain, String displayName, String clientSecretHash) {
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(displayName, "DisplayName cannot be null");
        Objects.requireNonNull(clientSecretHash, "ClientSecretHash cannot be null");

        if (domain.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("DisplayName cannot be blank");
        }
        if (clientSecretHash.isBlank()) {
            throw new IllegalArgumentException("ClientSecretHash cannot be blank");
        }

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        return new Site(id, accountId, domain.toLowerCase().trim(), clientSecretHash,
                displayName.trim(), true, now, now);
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
        return create(accountId, domain, displayName, hashedSecret);
    }

    public void updateDisplayName(String newDisplayName) {
        Objects.requireNonNull(newDisplayName, "DisplayName cannot be null");
        if (newDisplayName.isBlank()) {
            throw new IllegalArgumentException("DisplayName cannot be blank");
        }
        this.displayName = newDisplayName.trim();
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        if (!this.isActive) {
            throw new IllegalStateException("Site is already deactivated");
        }
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    public void activate() {
        if (this.isActive) {
            throw new IllegalStateException("Site is already active");
        }
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean canAuthenticate() {
        return this.isActive;
    }

    public boolean verifySecret(String providedSecret) {
        return new SiteCredentials(domain, clientSecretHash).verifySecret(providedSecret);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
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
