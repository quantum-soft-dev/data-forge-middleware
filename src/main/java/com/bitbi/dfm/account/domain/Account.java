package com.bitbi.dfm.account.domain;

import com.bitbi.dfm.account.infrastructure.CompanyConverter;
import com.bitbi.dfm.account.infrastructure.PhoneConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account aggregate root representing a system user.
 * <p>
 * Manages user identity, email validation, and soft delete behavior.
 * An account can own multiple sites for data upload.
 * </p>
 *
 * <h3>Business Rules (Invariants):</h3>
 * <ul>
 *   <li>Email must be unique across all accounts</li>
 *   <li>When deactivated, all associated sites must be deactivated (cascading)</li>
 *   <li>Deactivated accounts cannot create new sites or batches</li>
 *   <li>Existing active batches continue processing when account is deactivated</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class Account {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "phone", length = 50)
    @Convert(converter = PhoneConverter.class)
    private Phone phone;

    @Column(name = "company", length = 255)
    @Convert(converter = CompanyConverter.class)
    private Company company;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "identity_provider_user_id", length = 64, unique = true)
    private String identityProviderUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Private constructor for JPA.
     */
    protected Account(UUID id, String email, String name, Phone phone, Company company,
                      Boolean isActive, String identityProviderUserId, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.phone = phone;
        this.company = company;
        this.isActive = isActive;
        this.identityProviderUserId = identityProviderUserId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Create new account with validation.
     *
     * @param email   user's email address
     * @param name    user's display name
     * @param phone   user's phone number (optional)
     * @param company user's company name (optional)
     * @return new Account instance
     * @throws IllegalArgumentException if email, name, phone, or company is invalid
     */
    public static Account create(String email, String name, String phone, String company) {
        Objects.requireNonNull(email, "Email cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");

        validateEmail(email);
        validateName(name);

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        // Value Objects handle validation internally
        Phone phoneVO = Phone.of(phone);
        Company companyVO = Company.of(company);

        return new Account(id, email.toLowerCase().trim(), name.trim(),
                          phoneVO, companyVO, true, null, now, now);
    }

    /**
     * Create new account with Auth0 identity provider integration.
     * This is the preferred factory method for new accounts.
     *
     * @param identityProviderUserId Auth0 user ID (format: auth0|{alphanumeric})
     * @param email   user's email address
     * @param name    user's display name
     * @param phone   user's phone number (optional)
     * @param company user's company name (optional)
     * @return new Account instance with Auth0 linkage
     * @throws IllegalArgumentException if identityProviderUserId is invalid or other params fail validation
     */
    public static Account createWithIdentityProvider(String identityProviderUserId, String email,
                                              String name, String phone, String company) {
        Objects.requireNonNull(identityProviderUserId, "Identity provider user ID cannot be null for new accounts");

        if (identityProviderUserId.isBlank()) {
            throw new IllegalArgumentException("Identity provider user ID cannot be blank");
        }

        if (identityProviderUserId.length() > 64) {
            throw new IllegalArgumentException("Identity provider user ID cannot exceed 64 characters");
        }

        Objects.requireNonNull(email, "Email cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");

        validateEmail(email);
        validateName(name);

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        // Value Objects handle validation internally
        Phone phoneVO = Phone.of(phone);
        Company companyVO = Company.of(company);

        return new Account(id, email.toLowerCase().trim(), name.trim(),
                          phoneVO, companyVO, true, identityProviderUserId, now, now);
    }

    /**
     * Update account name.
     *
     * @param newName new display name
     * @throws IllegalArgumentException if name is invalid
     */
    public void updateName(String newName) {
        Objects.requireNonNull(newName, "Name cannot be null");
        validateName(newName);
        this.name = newName.trim();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Update account phone number.
     *
     * @param newPhone new phone number (can be null)
     * @throws IllegalArgumentException if phone format is invalid
     */
    public void updatePhone(String newPhone) {
        this.phone = Phone.of(newPhone);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Update account company name.
     *
     * @param newCompany new company name (can be null)
     * @throws IllegalArgumentException if company name is invalid
     */
    public void updateCompany(String newCompany) {
        this.company = Company.of(newCompany);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Soft delete account by setting isActive to false.
     * This should trigger cascading deactivation of all associated sites.
     */
    public void deactivate() {
        if (!this.isActive) {
            throw new IllegalStateException("Account is already deactivated");
        }
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
        // Domain event: AccountDeactivatedEvent should be published
    }

    /**
     * Reactivate previously deactivated account.
     */
    public void activate() {
        if (this.isActive) {
            throw new IllegalStateException("Account is already active");
        }
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Associate existing account with Auth0 identity provider user.
     *
     * @param identityProviderUserId Auth0 user ID (format: auth0|{alphanumeric})
     * @throws IllegalArgumentException if identityProviderUserId is invalid
     * @throws IllegalStateException if account is already linked to an identity provider
     */
    public void linkIdentityProvider(String identityProviderUserId) {
        Objects.requireNonNull(identityProviderUserId, "Identity provider user ID cannot be null");

        if (this.identityProviderUserId != null) {
            throw new IllegalStateException("Account is already linked to identity provider user: " + this.identityProviderUserId);
        }

        if (identityProviderUserId.isBlank()) {
            throw new IllegalArgumentException("Identity provider user ID cannot be blank");
        }

        if (identityProviderUserId.length() > 64) {
            throw new IllegalArgumentException("Identity provider user ID cannot exceed 64 characters");
        }

        this.identityProviderUserId = identityProviderUserId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Check if account is integrated with an identity provider (Auth0).
     *
     * @return true if account has identity provider user ID
     */
    public boolean hasIdentityProviderIntegration() {
        return identityProviderUserId != null;
    }

    /**
     * Validate email format.
     *
     * @param email email to validate
     * @throws IllegalArgumentException if email format is invalid
     */
    private static void validateEmail(String email) {
        if (email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }

        if (email.length() > 255) {
            throw new IllegalArgumentException("Email cannot exceed 255 characters");
        }
    }

    /**
     * Validate name.
     *
     * @param name name to validate
     * @throws IllegalArgumentException if name is invalid
     */
    private static void validateName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be blank");
        }

        if (name.length() > 255) {
            throw new IllegalArgumentException("Name cannot exceed 255 characters");
        }
    }

    /**
     * Check if account can create new resources (sites, batches).
     *
     * @return true if account is active
     */
    public boolean canCreateResources() {
        return this.isActive;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
