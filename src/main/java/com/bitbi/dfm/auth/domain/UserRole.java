package com.bitbi.dfm.auth.domain;

/**
 * User roles supported by Auth0 RBAC.
 * <p>
 * These roles must be pre-configured in Auth0 Dashboard under "User Management" → "Roles".
 * The Auth0 Login Action should be configured to add roles to the JWT access token.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum UserRole {
    /**
     * Regular user role.
     * Can manage their own sites, upload files, view history.
     */
    USER("ROLE_USER"),

    /**
     * Administrator role.
     * Can manage all accounts, create users, lock/unlock accounts.
     */
    ADMIN("ROLE_ADMIN");

    private final String auth0RoleName;

    UserRole(String auth0RoleName) {
        this.auth0RoleName = auth0RoleName;
    }

    /**
     * Get the Auth0 role name as stored in Auth0 Dashboard.
     *
     * @return Auth0 role name (e.g., "ROLE_USER", "ROLE_ADMIN")
     */
    public String getAuth0RoleName() {
        return auth0RoleName;
    }

    /**
     * Find UserRole by Auth0 role name.
     *
     * @param auth0RoleName Auth0 role name (e.g., "ROLE_USER")
     * @return matching UserRole
     * @throws IllegalArgumentException if role name not found
     */
    public static UserRole fromAuth0RoleName(String auth0RoleName) {
        for (UserRole role : values()) {
            if (role.auth0RoleName.equals(auth0RoleName)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown Auth0 role name: " + auth0RoleName);
    }
}
