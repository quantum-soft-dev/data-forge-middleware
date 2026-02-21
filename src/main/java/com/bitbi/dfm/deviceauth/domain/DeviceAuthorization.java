package com.bitbi.dfm.deviceauth.domain;

import com.bitbi.dfm.site.domain.SiteType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a device authorization request.
 * <p>
 * Stores pending authorization requests initiated by headless clients.
 * When user approves, a new site is created and credentials are stored.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "device_authorizations")
@Getter
@NoArgsConstructor
public class DeviceAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Secret device code for polling (64 characters).
     * Must be kept secret by the device.
     */
    @Column(name = "device_code", nullable = false, unique = true, length = 64)
    private String deviceCode;

    /**
     * Human-readable user code (format: XXXX-XXXX).
     * Displayed to user for manual entry.
     */
    @Column(name = "user_code", nullable = false, unique = true, length = 10)
    private String userCode;

    /**
     * Requested site name (will be created on approval).
     */
    @Column(name = "site_name", nullable = false, length = 100)
    private String siteName;

    /**
     * Optional site description.
     */
    @Column(name = "site_description", length = 500)
    private String siteDescription;

    /**
     * Account ID (set after user authentication).
     */
    @Column(name = "account_id")
    @Setter
    private UUID accountId;

    /**
     * Created site ID (set after approval).
     */
    @Column(name = "site_id")
    @Setter
    private UUID siteId;

    /**
     * Full domain for authentication (set after approval).
     */
    @Column(name = "domain", length = 200)
    @Setter
    private String domain;

    /**
     * BCrypt hash of client secret (set after approval).
     * Plain secret is returned only once to device.
     */
    @Column(name = "client_secret_hash", length = 200)
    @Setter
    private String clientSecretHash;

    /**
     * Site type requested by the device (immutable after creation).
     * Defaults to DBF for backward compatibility.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "site_type", nullable = false, length = 20)
    private SiteType siteType = SiteType.DBF;

    /**
     * Current status of the authorization request.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private DeviceAuthorizationStatus status = DeviceAuthorizationStatus.PENDING;

    /**
     * When the authorization codes expire.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * When the authorization was created.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the user approved (null if pending/denied/expired).
     */
    @Column(name = "approved_at")
    @Setter
    private Instant approvedAt;

    /**
     * Create a new device authorization request.
     *
     * @param deviceCode      secret device code for polling
     * @param userCode        human-readable code for user
     * @param siteName        requested site name
     * @param siteDescription optional site description
     * @param siteType        requested site type (null → DBF)
     * @param expiresAt       when codes expire
     */
    public DeviceAuthorization(String deviceCode, String userCode,
                               String siteName, String siteDescription,
                               SiteType siteType, Instant expiresAt) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.siteName = siteName;
        this.siteDescription = siteDescription;
        this.siteType = siteType != null ? siteType : SiteType.DBF;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.status = DeviceAuthorizationStatus.PENDING;
    }

    /**
     * Check if authorization has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if authorization is still pending.
     */
    public boolean isPending() {
        return status == DeviceAuthorizationStatus.PENDING && !isExpired();
    }

    /**
     * Check if authorization was approved.
     */
    public boolean isApproved() {
        return status == DeviceAuthorizationStatus.APPROVED;
    }
}
