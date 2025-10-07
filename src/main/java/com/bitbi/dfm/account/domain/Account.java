package com.bitbi.dfm.account.domain;

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

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Private constructor for JPA.
     */
    protected Account(UUID id, String email, String name, Boolean isActive,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Create new account with validation.
     *
     * @param email user's email address
     * @param name  user's display name
     * @return new Account instance
     * @throws IllegalArgumentException if email or name is invalid
     */
    public static Account create(String email, String name) {
        Objects.requireNonNull(email, "Email cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");

        validateEmail(email);
        validateName(name);

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        return new Account(id, email.toLowerCase().trim(), name.trim(), true, now, now);
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
