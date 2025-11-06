package com.bitbi.dfm.auth.config;

import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.net.AuthRequest;
import com.auth0.net.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

import java.time.Instant;

/**
 * Auth0 Management API configuration.
 * <p>
 * Provides ManagementAPI bean with cached token provider for making
 * Auth0 Management API calls (create users, update users, etc.).
 * </p>
 * <p>
 * Token Caching Strategy:
 * - Management API tokens are valid for 24 hours
 * - Tokens are cached with 5-minute buffer before expiry
 * - Automatic token refresh when expired
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@EnableRetry
public class Auth0Configuration {

    private static final Logger logger = LoggerFactory.getLogger(Auth0Configuration.class);

    @Value("${auth0.domain}")
    private String domain;

    @Value("${auth0.management-client-id}")
    private String managementClientId;

    @Value("${auth0.management-client-secret}")
    private String managementClientSecret;

    /**
     * Creates Auth0 ManagementAPI bean with cached token provider.
     * <p>
     * The ManagementAPI client is used for:
     * - Creating users (createAccountWithAuth0)
     * - Blocking/unblocking users (lockAccount/unlockAccount)
     * - Generating password reset tickets (resetPassword)
     * - Updating user metadata (linking accountId)
     * </p>
     *
     * @return Configured ManagementAPI instance
     */
    @Bean
    public ManagementAPI managementAPI() {
        logger.info("Initializing Auth0 ManagementAPI for domain: {}", domain);

        // Create cached token provider
        TokenProvider tokenProvider = new CachedAuth0TokenProvider(
                domain,
                managementClientId,
                managementClientSecret
        );

        // Create ManagementAPI with token provider
        return ManagementAPI.newBuilder(domain, tokenProvider).build();
    }

    /**
     * Cached token provider for Auth0 Management API.
     * <p>
     * Implements token caching to avoid unnecessary Authentication API calls.
     * Tokens are cached for 24 hours minus 5-minute buffer.
     * </p>
     */
    private static class CachedAuth0TokenProvider implements TokenProvider {

        private static final Logger logger = LoggerFactory.getLogger(CachedAuth0TokenProvider.class);

        private final AuthAPI authAPI;
        private final String domain;

        private String cachedToken;
        private Instant tokenExpiry;

        public CachedAuth0TokenProvider(String domain, String clientId, String clientSecret) {
            this.domain = domain;
            this.authAPI = AuthAPI.newBuilder(domain, clientId, clientSecret).build();
            logger.info("Auth0 TokenProvider initialized for domain: {}", domain);
        }

        @Override
        public String getToken() {
            // Check if token is expired or missing
            if (cachedToken == null || Instant.now().isAfter(tokenExpiry)) {
                refreshToken();
            }
            return cachedToken;
        }

        /**
         * Refresh Auth0 Management API token.
         * <p>
         * Requests new token from Auth0 Authentication API with audience
         * set to Management API v2 endpoint.
         * </p>
         */
        private void refreshToken() {
            try {
                logger.info("Refreshing Auth0 Management API token for domain: {}", domain);

                // Request token for Management API v2
                AuthRequest request = authAPI.requestToken("https://" + domain + "/api/v2/");
                TokenHolder holder = request.execute().getBody();

                // Cache token with 5-minute buffer before expiry
                this.cachedToken = holder.getAccessToken();
                this.tokenExpiry = Instant.now().plusSeconds(holder.getExpiresIn() - 300);

                logger.info("Auth0 Management API token refreshed successfully. Expires at: {}", tokenExpiry);

            } catch (Auth0Exception e) {
                logger.error("Failed to refresh Auth0 Management API token: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to obtain Auth0 Management API token", e);
            }
        }
    }
}
