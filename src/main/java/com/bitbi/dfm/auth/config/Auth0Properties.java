package com.bitbi.dfm.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Auth0 integration.
 * <p>
 * These properties are externalized to application.yml under the prefix "auth0".
 * Values should be sourced from environment variables in production to avoid
 * committing sensitive credentials to version control.
 * </p>
 *
 * <h3>Required Environment Variables (Production):</h3>
 * <ul>
 *   <li>AUTH0_DOMAIN - Your Auth0 tenant domain (e.g., dev-abc123.us.auth0.com)</li>
 *   <li>AUTH0_MANAGEMENT_CLIENT_ID - Machine-to-Machine application client ID</li>
 *   <li>AUTH0_MANAGEMENT_CLIENT_SECRET - Machine-to-Machine application client secret</li>
 *   <li>AUTH0_MANAGEMENT_AUDIENCE - Auth0 Management API audience (https://{domain}/api/v2/)</li>
 *   <li>AUTH0_API_AUDIENCE - Your API identifier (e.g., https://api.dataforge.com)</li>
 * </ul>
 *
 * <h3>Example Configuration (application.yml):</h3>
 * <pre>
 * auth0:
 *   domain: ${AUTH0_DOMAIN:dev-abc123.us.auth0.com}
 *   management:
 *     client-id: ${AUTH0_MANAGEMENT_CLIENT_ID}
 *     client-secret: ${AUTH0_MANAGEMENT_CLIENT_SECRET}
 *     audience: ${AUTH0_MANAGEMENT_AUDIENCE:https://dev-abc123.us.auth0.com/api/v2/}
 *   api:
 *     audience: ${AUTH0_API_AUDIENCE:https://api.dataforge.com}
 *     issuer: ${AUTH0_API_ISSUER:https://dev-abc123.us.auth0.com/}
 * </pre>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "auth0")
@Validated
public record Auth0Properties(
        @NotBlank(message = "Auth0 domain is required")
        String domain,

        @NotNull(message = "Auth0 management properties are required")
        Management management,

        @NotNull(message = "Auth0 API properties are required")
        Api api
) {

    /**
     * Auth0 Management API credentials.
     * <p>
     * These credentials are for the Machine-to-Machine (M2M) application
     * that has permissions to call the Auth0 Management API for user CRUD operations.
     * </p>
     *
     * @param clientId                 Machine-to-Machine application client ID
     * @param clientSecret             Machine-to-Machine application client secret (keep secure!)
     * @param audience                 Auth0 Management API audience (https://{domain}/api/v2/)
     * @param tokenExpiryBufferSeconds Buffer time before token expiry to trigger refresh (default: 3600 = 1 hour)
     */
    public record Management(
            @NotBlank(message = "Auth0 Management API client ID is required")
            String clientId,

            @NotBlank(message = "Auth0 Management API client secret is required")
            String clientSecret,

            @NotBlank(message = "Auth0 Management API audience is required")
            String audience,

            Long tokenExpiryBufferSeconds
    ) {
        /**
         * Get the token expiry buffer in seconds.
         * Returns default of 3600 (1 hour) if not configured.
         *
         * @return buffer seconds before token expiry to trigger refresh
         */
        public long getTokenExpiryBufferSeconds() {
            return tokenExpiryBufferSeconds != null ? tokenExpiryBufferSeconds : 3600L;
        }
    }

    /**
     * Auth0 API configuration for JWT validation.
     * <p>
     * These settings are used by Spring Security OAuth2 Resource Server
     * to validate JWT tokens issued by Auth0.
     * </p>
     *
     * @param audience        API identifier registered in Auth0 (e.g., https://api.dataforge.com)
     * @param issuer          Auth0 tenant issuer URL (e.g., https://dev-abc123.us.auth0.com/)
     * @param claimsNamespace Namespace prefix for custom claims in JWT (e.g., https://test.dfm.bitbi.io)
     */
    public record Api(
            @NotBlank(message = "Auth0 API audience is required")
            String audience,

            @NotBlank(message = "Auth0 API issuer is required")
            String issuer,

            @NotBlank(message = "Auth0 claims namespace is required")
            String claimsNamespace
    ) {
        /**
         * Get the full claim name for roles.
         * @return roles claim name (e.g., https://test.dfm.bitbi.io/roles)
         */
        public String rolesClaim() {
            return claimsNamespace + "/roles";
        }

        /**
         * Get the full claim name for accountId.
         * @return accountId claim name (e.g., https://test.dfm.bitbi.io/accountId)
         */
        public String accountIdClaim() {
            return claimsNamespace + "/accountId";
        }

        /**
         * Get the full claim name for email.
         * @return email claim name (e.g., https://test.dfm.bitbi.io/email)
         */
        public String emailClaim() {
            return claimsNamespace + "/email";
        }
    }

    /**
     * Get the full Auth0 Management API base URL.
     *
     * @return Management API base URL (https://{domain}/api/v2/)
     */
    public String getManagementApiBaseUrl() {
        return "https://" + domain + "/api/v2/";
    }

    /**
     * Get the full Auth0 issuer URL.
     *
     * @return Issuer URL (https://{domain}/)
     */
    public String getIssuerUrl() {
        return api.issuer();
    }

    /**
     * Get the full Auth0 token endpoint URL.
     *
     * @return Token endpoint URL (https://{domain}/oauth/token)
     */
    public String getTokenEndpoint() {
        return "https://" + domain + "/oauth/token";
    }
}
