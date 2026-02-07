package com.bitbi.dfm.account.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AdminActionLog entity tracks administrative actions performed on user accounts.
 * <p>
 * This entity provides a complete audit trail for security and compliance purposes.
 * All account management operations (create, lock, unlock, password reset) are logged.
 * </p>
 * <p>
 * <b>Admin Identity Architecture:</b>
 * Admins are Auth0 users with ROLE_ADMIN but have NO corresponding Account record in PostgreSQL.
 * Therefore, admin_account_id is ALWAYS NULL. Admin identity is tracked via Auth0 JWT claims (email, sub).
 * Regular users (ROLE_USER) have Account records with bidirectional Auth0 mapping.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "admin_action_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AdminActionType actionType;

    @Column(name = "target_account_id", nullable = true)
    private UUID targetAccountId;

    @Column(name = "admin_account_id", nullable = true)
    private UUID adminAccountId;

    @Column(name = "target_site_id", nullable = true)
    private UUID targetSiteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Private constructor for building log entries.
     * Parameter order matches factory methods for consistency.
     */
    private AdminActionLog(AdminActionType actionType, UUID targetAccountId, UUID targetSiteId,
                           UUID adminAccountId, ActionStatus status, String errorMessage,
                           String ipAddress, String userAgent) {
        this.actionType = actionType;
        this.targetAccountId = targetAccountId;
        this.targetSiteId = targetSiteId;
        this.adminAccountId = adminAccountId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = Instant.now();
    }

    /**
     * Factory method for successful action (account-level).
     *
     * @param actionType    type of administrative action
     * @param targetAccountId UUID of the account being acted upon
     * @param adminAccountId  UUID of the admin performing the action
     * @param ipAddress       IP address of the admin
     * @param userAgent       User agent of the admin's browser
     * @return new AdminActionLog instance with SUCCESS status
     */
    public static AdminActionLog success(AdminActionType actionType, UUID targetAccountId,
                                          UUID adminAccountId, String ipAddress, String userAgent) {
        return new AdminActionLog(actionType, targetAccountId, null,
                adminAccountId, ActionStatus.SUCCESS, null, ipAddress, userAgent);
    }

    /**
     * Factory method for successful site action.
     *
     * @param actionType    type of administrative action
     * @param targetAccountId UUID of the account owning the site
     * @param targetSiteId    UUID of the site being acted upon
     * @param adminAccountId  UUID of the admin performing the action
     * @param ipAddress       IP address of the admin
     * @param userAgent       User agent of the admin's browser
     * @return new AdminActionLog instance with SUCCESS status
     */
    public static AdminActionLog successForSite(AdminActionType actionType, UUID targetAccountId,
                                                 UUID targetSiteId, UUID adminAccountId,
                                                 String ipAddress, String userAgent) {
        return new AdminActionLog(actionType, targetAccountId, targetSiteId,
                adminAccountId, ActionStatus.SUCCESS, null, ipAddress, userAgent);
    }

    /**
     * Factory method for failed action (account-level).
     *
     * @param actionType    type of administrative action
     * @param targetAccountId UUID of the account being acted upon
     * @param adminAccountId  UUID of the admin performing the action
     * @param errorMessage    error message explaining the failure
     * @param ipAddress       IP address of the admin
     * @param userAgent       User agent of the admin's browser
     * @return new AdminActionLog instance with FAILED status
     */
    public static AdminActionLog failure(AdminActionType actionType, UUID targetAccountId,
                                          UUID adminAccountId, String errorMessage,
                                          String ipAddress, String userAgent) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Error message cannot be null or blank for failed action");
        }
        return new AdminActionLog(actionType, targetAccountId, null,
                adminAccountId, ActionStatus.FAILED, errorMessage, ipAddress, userAgent);
    }

    /**
     * Factory method for failed site action.
     *
     * @param actionType    type of administrative action
     * @param targetAccountId UUID of the account owning the site
     * @param targetSiteId    UUID of the site being acted upon
     * @param adminAccountId  UUID of the admin performing the action
     * @param errorMessage    error message explaining the failure
     * @param ipAddress       IP address of the admin
     * @param userAgent       User agent of the admin's browser
     * @return new AdminActionLog instance with FAILED status
     */
    public static AdminActionLog failureForSite(AdminActionType actionType, UUID targetAccountId,
                                                 UUID targetSiteId, UUID adminAccountId,
                                                 String errorMessage, String ipAddress, String userAgent) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Error message cannot be null or blank for failed action");
        }
        return new AdminActionLog(actionType, targetAccountId, targetSiteId,
                adminAccountId, ActionStatus.FAILED, errorMessage, ipAddress, userAgent);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
