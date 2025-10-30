package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.shared.config.KeycloakAdminConfig;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * KeycloakAdminClient wraps Keycloak Admin REST API operations.
 * <p>
 * This wrapper translates JAX-RS exceptions to domain-friendly exceptions
 * and provides a cleaner API for account management operations.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class KeycloakAdminClient {

    private final Keycloak keycloak;
    private final String realm;

    public KeycloakAdminClient(Keycloak keycloak, KeycloakAdminConfig config) {
        this.keycloak = keycloak;
        this.realm = config.getRealm();
    }

    /**
     * Create a new user in Keycloak.
     *
     * @param email            user's email
     * @param username         username (typically same as email)
     * @param temporaryPassword temporary password
     * @param enabled          whether user is enabled
     * @return Keycloak user ID (UUID)
     * @throws KeycloakOperationException if user creation fails
     */
    public String createUser(String email, String username, String temporaryPassword, boolean enabled) {
        try {
            UserRepresentation user = new UserRepresentation();
            user.setEnabled(enabled);
            user.setUsername(username);
            user.setEmail(email);
            user.setEmailVerified(true);

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(temporaryPassword);
            credential.setTemporary(true);
            user.setCredentials(List.of(credential));

            Response response = keycloak.realm(realm).users().create(user);

            if (response.getStatus() != 201) {
                throw new KeycloakOperationException("Failed to create user: HTTP " + response.getStatus());
            }

            String location = response.getHeaderString("Location");
            if (location == null) {
                throw new KeycloakOperationException("No Location header in create user response");
            }

            String userId = location.substring(location.lastIndexOf('/') + 1);
            response.close();
            return userId;
        } catch (Exception e) {
            if (e instanceof KeycloakOperationException) {
                throw e;
            }
            throw new KeycloakOperationException("Error creating Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Enable a user in Keycloak (unlock).
     *
     * @param keycloakUserId Keycloak user ID
     * @throws KeycloakOperationException if operation fails
     */
    public void enableUser(String keycloakUserId) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEnabled(true);
            userResource.update(user);
        } catch (Exception e) {
            throw new KeycloakOperationException("Error enabling Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Disable a user in Keycloak (lock).
     *
     * @param keycloakUserId Keycloak user ID
     * @throws KeycloakOperationException if operation fails
     */
    public void disableUser(String keycloakUserId) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEnabled(false);
            userResource.update(user);
        } catch (Exception e) {
            throw new KeycloakOperationException("Error disabling Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Reset user's password to a new temporary password.
     *
     * @param keycloakUserId    Keycloak user ID
     * @param temporaryPassword new temporary password
     * @throws KeycloakOperationException if operation fails
     */
    public void resetPassword(String keycloakUserId, String temporaryPassword) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(temporaryPassword);
            credential.setTemporary(true);

            userResource.resetPassword(credential);
        } catch (Exception e) {
            throw new KeycloakOperationException("Error resetting Keycloak user password: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a user from Keycloak (rollback operation).
     *
     * @param keycloakUserId Keycloak user ID
     * @throws KeycloakOperationException if operation fails
     */
    public void deleteUser(String keycloakUserId) {
        try {
            keycloak.realm(realm).users().get(keycloakUserId).remove();
        } catch (Exception e) {
            throw new KeycloakOperationException("Error deleting Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Get user representation from Keycloak.
     *
     * @param keycloakUserId Keycloak user ID
     * @return UserRepresentation
     * @throws KeycloakOperationException if operation fails
     */
    public UserRepresentation getUser(String keycloakUserId) {
        try {
            return keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
        } catch (Exception e) {
            throw new KeycloakOperationException("Error fetching Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Assign a realm-level role to a user in Keycloak.
     * <p>
     * This method retrieves the role from the realm and assigns it to the user.
     * Common roles: "USER", "ADMIN"
     * </p>
     *
     * @param keycloakUserId Keycloak user ID
     * @param roleName       Name of the role to assign (e.g., "USER", "ADMIN")
     * @throws KeycloakOperationException if operation fails
     */
    public void assignRole(String keycloakUserId, String roleName) {
        try {
            // Get the role from the realm
            RoleRepresentation role = keycloak.realm(realm)
                    .roles()
                    .get(roleName)
                    .toRepresentation();

            if (role == null) {
                throw new KeycloakOperationException("Role not found in realm: " + roleName);
            }

            // Assign the role to the user at realm level
            keycloak.realm(realm)
                    .users()
                    .get(keycloakUserId)
                    .roles()
                    .realmLevel()
                    .add(Collections.singletonList(role));

        } catch (Exception e) {
            if (e instanceof KeycloakOperationException) {
                throw e;
            }
            throw new KeycloakOperationException("Error assigning role to Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Get all realm-level roles assigned to a user.
     *
     * @param keycloakUserId Keycloak user ID
     * @return List of role names
     * @throws KeycloakOperationException if operation fails
     */
    public List<String> getRoles(String keycloakUserId) {
        try {
            List<RoleRepresentation> roles = keycloak.realm(realm)
                    .users()
                    .get(keycloakUserId)
                    .roles()
                    .realmLevel()
                    .listAll();

            return roles.stream()
                    .map(RoleRepresentation::getName)
                    .toList();
        } catch (Exception e) {
            throw new KeycloakOperationException("Error getting roles for Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Update user attributes in Keycloak (for bidirectional mapping).
     *
     * @param keycloakUserId Keycloak user ID
     * @param accountId      PostgreSQL account ID to store in attributes
     * @throws KeycloakOperationException if operation fails
     */
    public void updateUserAttributes(String keycloakUserId, String accountId) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.singleAttribute("accountId", accountId);
            userResource.update(user);
        } catch (Exception e) {
            throw new KeycloakOperationException("Error updating Keycloak user attributes: " + e.getMessage(), e);
        }
    }

    /**
     * Get the last login timestamp for a Keycloak user.
     * <p>
     * Retrieves the most recent login time from active user sessions.
     * If no sessions exist, returns null.
     * </p>
     *
     * @param keycloakUserId Keycloak user ID
     * @return Last login timestamp in milliseconds since epoch, or null if never logged in
     * @throws KeycloakOperationException if operation fails
     */
    public Long getLastLogin(String keycloakUserId) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);

            // Get user sessions - returns both active and recent sessions
            var sessions = userResource.getUserSessions();

            if (sessions == null || sessions.isEmpty()) {
                // No sessions found, user has never logged in
                return null;
            }

            // Find the most recent login time from all sessions
            // getStart() returns long (primitive), so we filter by checking for 0 (default value)
            return sessions.stream()
                    .map(session -> session.getStart())
                    .filter(start -> start > 0)
                    .max(Long::compareTo)
                    .orElse(null);

        } catch (Exception e) {
            throw new KeycloakOperationException("Error fetching last login for Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Custom exception for Keycloak operations.
     */
    public static class KeycloakOperationException extends RuntimeException {
        public KeycloakOperationException(String message) {
            super(message);
        }

        public KeycloakOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
