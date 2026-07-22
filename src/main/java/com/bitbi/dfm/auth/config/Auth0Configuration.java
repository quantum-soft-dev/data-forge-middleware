package com.bitbi.dfm.auth.config;

import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.net.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Auth0 Management API configuration.
 * <p>
 * Provides ManagementAPI bean with automatic token management.
 * Tokens are refreshed automatically before expiration.
 * </p>
 * <p>
 * Token Caching Strategy:
 * - Management API tokens are valid for 24 hours
 * - Tokens are cached with 5-minute buffer before expiry
 * - Automatic token refresh via scheduled task
 * - Thread-safe token management with ReentrantLock
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@EnableRetry
@EnableScheduling
public class Auth0Configuration {

    private static final Logger logger = LoggerFactory.getLogger(Auth0Configuration.class);

    @Value("${auth0.domain:}")
    private String domain;

    @Value("${auth0.management.client-id:}")
    private String managementClientId;

    @Value("${auth0.management.client-secret:}")
    private String managementClientSecret;

    @Value("${auth0.management.token-expiry-buffer-seconds:3600}")
    private long tokenExpiryBufferSeconds;

    private ManagementAPI managementAPI;
    private AuthAPI authAPI;
    private String cachedToken;
    private Instant tokenExpiry;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * Creates Auth0 ManagementAPI bean with automatic token management.
     * <p>
     * The ManagementAPI client is used for:
     * - Creating users (createAccount)
     * - Blocking/unblocking users (lockAccount/unlockAccount)
     * - Generating password reset tickets (resetPassword)
     * - Updating user metadata (linking accountId)
     * </p>
     * <p>
     * This bean is conditional - only created when auth0.management.client-id is set.
     * In test environments without real Auth0 credentials, this bean will not be created.
     * </p>
     *
     * @return Configured ManagementAPI instance
     */
    @Bean
    @Profile("!test")
    @ConditionalOnExpression("'${auth0.management.client-id:}' != ''")
    public ManagementAPI managementAPI() {
        logger.info("Initializing Auth0 ManagementAPI for domain: {}", domain);

        // Initialize AuthAPI for token requests
        this.authAPI = AuthAPI.newBuilder(domain, managementClientId, managementClientSecret).build();

        // Get initial token
        refreshToken();

        // Create ManagementAPI with initial token
        this.managementAPI = ManagementAPI.newBuilder(domain, cachedToken).build();

        logger.info("Auth0 ManagementAPI initialized successfully");
        return this.managementAPI;
    }

    /**
     * Scheduled task to refresh Auth0 Management API token.
     * <p>
     * Runs every 23 hours to refresh token before 24-hour expiry.
     * Ensures ManagementAPI always has a valid token.
     * </p>
     */
    @Scheduled(fixedRate = 23 * 60 * 60 * 1000) // 23 hours
    public void refreshTokenScheduled() {
        if (managementAPI != null) {
            logger.info("Scheduled Auth0 token refresh triggered");
            refreshToken();
            // Update ManagementAPI with new token
            managementAPI.setApiToken(cachedToken);
        }
    }

    /**
     * Refresh Auth0 Management API token.
     * <p>
     * Requests new token from Auth0 Authentication API with audience
     * set to Management API v2 endpoint.
     * Thread-safe with ReentrantLock.
     * </p>
     */
    private void refreshToken() {
        tokenLock.lock();
        try {
            // Check if token is still valid (5-minute buffer)
            if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
                logger.debug("Auth0 token still valid, skipping refresh");
                return;
            }

            logger.info("Refreshing Auth0 Management API token for domain: {}", domain);

            // Request token for Management API v2
            Request<TokenHolder> request = authAPI.requestToken("https://" + domain + "/api/v2/");
            TokenHolder holder = request.execute().getBody();

            // Cache token with configurable buffer before expiry (default: 1 hour)
            this.cachedToken = holder.getAccessToken();
            this.tokenExpiry = Instant.now().plusSeconds(holder.getExpiresIn() - tokenExpiryBufferSeconds);

            logger.info("Auth0 Management API token refreshed successfully. Expires at: {} (buffer: {}s)",
                    tokenExpiry, tokenExpiryBufferSeconds);

        } catch (Auth0Exception e) {
            logger.error("Failed to refresh Auth0 Management API token: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to obtain Auth0 Management API token", e);
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Get current Auth0 Management API token.
     * <p>
     * Automatically refreshes if expired.
     * Thread-safe.
     * </p>
     *
     * @return Valid Auth0 Management API token
     */
    public String getToken() {
        // Check if token needs refresh
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry)) {
            refreshToken();
            if (managementAPI != null) {
                managementAPI.setApiToken(cachedToken);
            }
        }
        return cachedToken;
    }
}
