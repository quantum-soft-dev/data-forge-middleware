package com.bitbi.dfm.auth.infrastructure;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.filter.RolesFilter;
import com.auth0.client.mgmt.filter.UserFilter;
import com.auth0.exception.APIException;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.roles.Role;
import com.auth0.json.mgmt.roles.RolesPage;
import com.auth0.json.mgmt.tickets.PasswordChangeTicket;
import com.auth0.json.mgmt.users.User;
import com.bitbi.dfm.auth.application.Auth0TokenProvider;
import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Infrastructure wrapper for Auth0 Management API SDK.
 * <p>
 * Provides simplified, domain-oriented methods for user management operations
 * via the Auth0 Management API. Handles token management, error handling, and
 * logging for all Auth0 API calls.
 * </p>
 *
 * <h3>Operations:</h3>
 * <ul>
 *   <li>Create user with email and metadata</li>
 *   <li>Block/unblock user accounts</li>
 *   <li>Generate password reset tickets</li>
 *   <li>Get user by ID or email</li>
 *   <li>Update user metadata</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * <ul>
 *   <li>Auth0Exception: Wrapped and rethrown with context</li>
 *   <li>401 Unauthorized: Token refreshed and request retried once</li>
 *   <li>429 Rate Limit: Logged with retry recommendation</li>
 *   <li>4xx Client Errors: Invalid requests logged</li>
 *   <li>5xx Server Errors: Auth0 service issues logged</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class Auth0ManagementApiClient {

    private static final Logger logger = LoggerFactory.getLogger(Auth0ManagementApiClient.class);

    private final Auth0Properties properties;
    private final Auth0TokenProvider tokenProvider;

    /**
     * Database connection name in Auth0 (default: "Username-Password-Authentication").
     * Users created via this client will use database authentication.
     */
    private static final String DATABASE_CONNECTION = "Username-Password-Authentication";

    /**
     * Password change ticket expiry time in seconds (24 hours).
     */
    private static final int PASSWORD_TICKET_TTL_SECONDS = 86400; // 24 hours

    /**
     * Cache for Auth0 role IDs (role name -> role ID).
     * Populated lazily on first role assignment to avoid API calls if roles are never used.
     */
    private final Map<String, String> roleIdCache = new ConcurrentHashMap<>();

    public Auth0ManagementApiClient(Auth0Properties properties, Auth0TokenProvider tokenProvider) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        logger.info("Auth0ManagementApiClient initialized for domain: {}", properties.domain());
    }

    /**
     * Functional interface for Auth0 API operations that can throw Auth0Exception.
     */
    @FunctionalInterface
    private interface Auth0Operation<T> {
        T execute(ManagementAPI mgmt) throws Auth0Exception;
    }

    /**
     * Execute an Auth0 API operation with automatic retry on token expiration.
     * <p>
     * If the operation fails with 401 Unauthorized, the token is refreshed
     * and the operation is retried once.
     * </p>
     *
     * @param operation   the Auth0 API operation to execute
     * @param operationName name of the operation for logging
     * @param <T>         return type of the operation
     * @return result of the operation
     * @throws Auth0Exception if operation fails after retry
     */
    private <T> T executeWithRetry(Auth0Operation<T> operation, String operationName) throws Auth0Exception {
        try {
            String accessToken = tokenProvider.getAccessToken();
            ManagementAPI mgmt = ManagementAPI.newBuilder(properties.domain(), accessToken).build();
            return operation.execute(mgmt);
        } catch (APIException e) {
            if (isTokenExpiredError(e)) {
                logger.warn("Auth0 token expired during {}, refreshing and retrying...", operationName);
                tokenProvider.refreshToken();
                String newAccessToken = tokenProvider.getAccessToken();
                ManagementAPI mgmt = ManagementAPI.newBuilder(properties.domain(), newAccessToken).build();
                return operation.execute(mgmt);
            }
            throw e;
        }
    }

    /**
     * Execute an Auth0 API operation (void) with automatic retry on token expiration.
     */
    private void executeWithRetryVoid(VoidAuth0Operation operation, String operationName) throws Auth0Exception {
        executeWithRetry(mgmt -> {
            operation.execute(mgmt);
            return null;
        }, operationName);
    }

    /**
     * Functional interface for void Auth0 API operations.
     */
    @FunctionalInterface
    private interface VoidAuth0Operation {
        void execute(ManagementAPI mgmt) throws Auth0Exception;
    }

    /**
     * Check if the exception indicates an expired or invalid token.
     */
    private boolean isTokenExpiredError(APIException e) {
        int statusCode = e.getStatusCode();
        String errorCode = e.getError();
        // 401 Unauthorized or specific token error codes
        return statusCode == 401
                || "invalid_token".equalsIgnoreCase(errorCode)
                || "expired_token".equalsIgnoreCase(errorCode);
    }

    /**
     * Create a new user in Auth0 database connection.
     * <p>
     * The user will be created with email verification disabled and blocked=false.
     * A password change ticket should be generated immediately after creation
     * to allow the user to set their password via Auth0 Universal Login.
     * </p>
     *
     * @param email     user's email address (must be unique)
     * @param name      user's display name
     * @param accountId PostgreSQL account UUID (stored in app_metadata)
     * @return Auth0 user ID (e.g., auth0|507f1f77bcf86cd799439011)
     * @throws Auth0Exception if user creation fails (e.g., duplicate email, invalid input)
     */
    public Auth0UserId createUser(String email, String name, String accountId) throws Auth0Exception {
        logger.info("Creating Auth0 user: email={}, name={}, accountId={}", email, name, accountId);

        try {
            return executeWithRetry(mgmt -> {
                User user = new User(DATABASE_CONNECTION);
                user.setEmail(email);
                user.setName(name);
                user.setEmailVerified(false); // User must verify email via password reset link
                user.setBlocked(false);

                // Store accountId in app_metadata for JWT claims (Auth0 Action will read this)
                user.setAppMetadata(Map.of("accountId", accountId));

                User createdUser = mgmt.users().create(user).execute().getBody();

                if (createdUser == null || createdUser.getId() == null) {
                    throw new Auth0Exception("User creation returned null response");
                }

                Auth0UserId userId = Auth0UserId.of(createdUser.getId());
                logger.info("Auth0 user created successfully: userId={}, email={}", userId, email);

                return userId;
            }, "createUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to create Auth0 user: email={}, error={}", email, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Create a new user in Auth0 with a specified password.
     * <p>
     * Creates a user with the given password and optionally sends a verification email.
     * The user will be able to log in immediately with the provided password.
     * </p>
     * <p>
     * This method includes automatic retry on token expiration (401 errors).
     * </p>
     *
     * @param email            user's email address (must be unique)
     * @param name             user's display name
     * @param password         user's initial password
     * @param sendVerifyEmail  if true, sends verification email to user
     * @return created Auth0 User object with ID
     * @throws Auth0Exception if user creation fails (e.g., duplicate email, invalid password)
     */
    public User createUserWithPassword(String email, String name, String password, boolean sendVerifyEmail) throws Auth0Exception {
        logger.info("Creating Auth0 user with password: email={}, name={}, verifyEmail={}", email, name, sendVerifyEmail);

        try {
            return executeWithRetry(mgmt -> {
                User user = new User(DATABASE_CONNECTION);
                user.setEmail(email);
                user.setName(name);
                user.setPassword(password.toCharArray());
                user.setVerifyEmail(sendVerifyEmail);
                user.setBlocked(false);

                User createdUser = mgmt.users().create(user).execute().getBody();

                if (createdUser == null || createdUser.getId() == null) {
                    throw new Auth0Exception("User creation returned null response");
                }

                logger.info("Auth0 user created successfully with password: userId={}, email={}", createdUser.getId(), email);
                return createdUser;
            }, "createUserWithPassword");

        } catch (Auth0Exception e) {
            logger.error("Failed to create Auth0 user with password: email={}, error={}", email, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Generate a password change ticket for a user.
     * <p>
     * Returns a URL that the user can visit to set their password via Auth0 Universal Login.
     * The ticket expires after 24 hours.
     * </p>
     *
     * @param userId Auth0 user ID
     * @return password change ticket URL (expires in 24 hours)
     * @throws Auth0Exception if ticket generation fails
     */
    public String createPasswordChangeTicket(Auth0UserId userId) throws Auth0Exception {
        return generatePasswordResetLink(userId, null);
    }

    /**
     * Generate a password reset link (password change ticket) for a user.
     * <p>
     * Returns a URL that the user can visit to set their password via Auth0 Universal Login.
     * The ticket expires after 24 hours. Optionally redirects user to resultUrl after password change.
     * </p>
     *
     * User Story: US3 - Admin Resets User Password
     *
     * @param userId    Auth0 user ID
     * @param resultUrl optional URL to redirect user after password change (can be null)
     * @return password change ticket URL (expires in 24 hours)
     * @throws Auth0Exception if ticket generation fails
     */
    public String generatePasswordResetLink(Auth0UserId userId, String resultUrl) throws Auth0Exception {
        logger.info("Generating password reset link for userId={}, resultUrl={}", userId, resultUrl);

        try {
            return executeWithRetry(mgmt -> {
                PasswordChangeTicket ticket = new PasswordChangeTicket(userId.value());
                ticket.setTTLSeconds(PASSWORD_TICKET_TTL_SECONDS);

                // Set result URL if provided (redirect after password change)
                if (resultUrl != null && !resultUrl.isBlank()) {
                    ticket.setResultUrl(resultUrl);
                }

                PasswordChangeTicket createdTicket = mgmt.tickets()
                        .requestPasswordChange(ticket)
                        .execute()
                        .getBody();

                if (createdTicket == null || createdTicket.getTicket() == null) {
                    throw new Auth0Exception("Password change ticket generation returned null");
                }

                String ticketUrl = createdTicket.getTicket();
                logger.info("Password reset link generated: userId={}, expiresIn=24h", userId);

                return ticketUrl;
            }, "generatePasswordResetLink");

        } catch (Auth0Exception e) {
            logger.error("Failed to generate password reset link: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Block a user account in Auth0.
     * <p>
     * Blocked users cannot log in. Existing sessions remain valid until token expiry.
     * </p>
     *
     * @param userId Auth0 user ID
     * @throws Auth0Exception if block operation fails
     */
    public void blockUser(Auth0UserId userId) throws Auth0Exception {
        logger.info("Blocking Auth0 user: userId={}", userId);

        try {
            executeWithRetryVoid(mgmt -> {
                User user = new User();
                user.setBlocked(true);
                mgmt.users().update(userId.value(), user).execute();
                logger.info("Auth0 user blocked successfully: userId={}", userId);
            }, "blockUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to block Auth0 user: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Unblock a user account in Auth0.
     * <p>
     * Unblocked users can log in again.
     * </p>
     *
     * @param userId Auth0 user ID
     * @throws Auth0Exception if unblock operation fails
     */
    public void unblockUser(Auth0UserId userId) throws Auth0Exception {
        logger.info("Unblocking Auth0 user: userId={}", userId);

        try {
            executeWithRetryVoid(mgmt -> {
                User user = new User();
                user.setBlocked(false);
                mgmt.users().update(userId.value(), user).execute();
                logger.info("Auth0 user unblocked successfully: userId={}", userId);
            }, "unblockUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to unblock Auth0 user: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get a user by Auth0 user ID.
     *
     * @param userId Auth0 user ID
     * @return User object with all details
     * @throws Auth0Exception if user not found or API call fails
     */
    public User getUser(Auth0UserId userId) throws Auth0Exception {
        logger.debug("Fetching Auth0 user: userId={}", userId);

        try {
            return executeWithRetry(mgmt -> {
                User user = mgmt.users().get(userId.value(), null).execute().getBody();

                if (user == null) {
                    throw new Auth0Exception("User not found: " + userId);
                }

                return user;
            }, "getUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to fetch Auth0 user: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Search for users by email.
     * <p>
     * Uses Lucene query syntax. Email must be exact match.
     * </p>
     *
     * @param email email address to search
     * @return list of matching users (typically 0 or 1 due to unique constraint)
     * @throws Auth0Exception if search fails
     */
    public List<User> getUsersByEmail(String email) throws Auth0Exception {
        logger.debug("Searching Auth0 users by email: email={}", email);

        try {
            return executeWithRetry(mgmt -> {
                // Lucene query: email:"user@example.com"
                UserFilter filter = new UserFilter()
                        .withQuery("email:\"" + email + "\"")
                        .withSearchEngine("v3");

                List<User> users = mgmt.users().list(filter).execute().getBody().getItems();

                logger.debug("Found {} Auth0 users with email: {}", users.size(), email);

                return users;
            }, "getUsersByEmail");

        } catch (Auth0Exception e) {
            logger.error("Failed to search Auth0 users: email={}, error={}", email, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update user metadata (app_metadata or user_metadata).
     * <p>
     * app_metadata is used for internal system data (accountId, roles).
     * user_metadata is for user-editable profile data.
     * </p>
     *
     * @param userId      Auth0 user ID
     * @param appMetadata application metadata to merge
     * @throws Auth0Exception if update fails
     */
    public void updateUserMetadata(Auth0UserId userId, Map<String, Object> appMetadata) throws Auth0Exception {
        logger.info("Updating Auth0 user metadata: userId={}, metadata={}", userId, appMetadata);

        try {
            executeWithRetryVoid(mgmt -> {
                User user = new User();
                user.setAppMetadata(appMetadata);
                mgmt.users().update(userId.value(), user).execute();
                logger.info("Auth0 user metadata updated successfully: userId={}", userId);
            }, "updateUserMetadata");

        } catch (Auth0Exception e) {
            logger.error("Failed to update Auth0 user metadata: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Delete a user from Auth0.
     * <p>
     * Permanently deletes the user account. This action cannot be undone.
     * All user data and metadata will be removed from Auth0.
     * </p>
     *
     * @param userId Auth0 user ID
     * @throws Auth0Exception if delete operation fails
     */
    public void deleteUser(Auth0UserId userId) throws Auth0Exception {
        logger.info("Deleting Auth0 user: userId={}", userId);

        try {
            executeWithRetryVoid(mgmt -> {
                mgmt.users().delete(userId.value()).execute();
                logger.info("Auth0 user deleted successfully: userId={}", userId);
            }, "deleteUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to delete Auth0 user: userId={}, error={}", userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Assign a role to a user in Auth0.
     * <p>
     * Looks up the role ID by name (cached after first lookup) and assigns it to the user.
     * The role must already exist in Auth0 Dashboard under "User Management" → "Roles".
     * </p>
     *
     * @param userId Auth0 user ID
     * @param role   UserRole to assign (USER or ADMIN)
     * @throws Auth0Exception if role assignment fails or role not found
     */
    public void assignRoleToUser(Auth0UserId userId, UserRole role) throws Auth0Exception {
        String roleName = role.getAuth0RoleName();
        logger.info("Assigning role to Auth0 user: userId={}, role={}", userId, roleName);

        try {
            executeWithRetryVoid(mgmt -> {
                // Get role ID (from cache or lookup)
                String roleId = getRoleId(mgmt, roleName);

                // Assign role to user
                mgmt.users().addRoles(userId.value(), Collections.singletonList(roleId)).execute();

                logger.info("Role assigned successfully: userId={}, role={}", userId, roleName);
            }, "assignRoleToUser");

        } catch (Auth0Exception e) {
            logger.error("Failed to assign role to Auth0 user: userId={}, role={}, error={}",
                    userId, roleName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get role ID by role name (cached).
     * <p>
     * Looks up the role ID from Auth0 and caches it for subsequent calls.
     * </p>
     *
     * @param mgmt     ManagementAPI instance
     * @param roleName Auth0 role name (e.g., "ROLE_USER")
     * @return Auth0 role ID
     * @throws Auth0Exception if role not found or lookup fails
     */
    private String getRoleId(ManagementAPI mgmt, String roleName) throws Auth0Exception {
        // Check cache first
        String cachedRoleId = roleIdCache.get(roleName);
        if (cachedRoleId != null) {
            logger.debug("Using cached role ID for {}: {}", roleName, cachedRoleId);
            return cachedRoleId;
        }

        // Lookup role by name
        logger.debug("Looking up role ID for: {}", roleName);

        RolesFilter filter = new RolesFilter().withName(roleName);
        RolesPage rolesPage = mgmt.roles().list(filter).execute().getBody();

        if (rolesPage == null || rolesPage.getItems() == null || rolesPage.getItems().isEmpty()) {
            throw new Auth0Exception("Role not found in Auth0: " + roleName);
        }

        // Find exact match (filter returns partial matches)
        Role foundRole = rolesPage.getItems().stream()
                .filter(r -> roleName.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new Auth0Exception("Role not found in Auth0: " + roleName));

        String roleId = foundRole.getId();

        // Cache for future use
        roleIdCache.put(roleName, roleId);
        logger.info("Cached role ID: {} -> {}", roleName, roleId);

        return roleId;
    }
}
