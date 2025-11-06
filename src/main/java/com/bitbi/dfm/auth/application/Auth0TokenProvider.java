package com.bitbi.dfm.auth.application;

import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.net.TokenRequest;
import com.bitbi.dfm.auth.config.Auth0Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service for obtaining and caching Auth0 Management API access tokens.
 * <p>
 * This service uses the OAuth 2.0 Client Credentials flow to obtain access tokens
 * from Auth0 for calling the Management API. Tokens are cached in memory for
 * 24 hours to minimize API calls and improve performance.
 * </p>
 *
 * <h3>Token Lifecycle:</h3>
 * <ul>
 *   <li>Token requested via Client Credentials flow (client_id + client_secret)</li>
 *   <li>Token cached in memory with 24-hour TTL (expires_in from Auth0)</li>
 *   <li>Token automatically refreshed when expired or within 5 minutes of expiry</li>
 *   <li>Thread-safe token refresh using ReentrantLock</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * <ul>
 *   <li>Auth0Exception: Wraps all Auth0 SDK exceptions</li>
 *   <li>Invalid credentials: Returns 401 Unauthorized from Auth0</li>
 *   <li>Network errors: Propagated to caller for retry logic</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class Auth0TokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(Auth0TokenProvider.class);

    /**
     * Buffer time before token expiry to trigger refresh (5 minutes).
     * Prevents using tokens that are about to expire.
     */
    private static final long EXPIRY_BUFFER_SECONDS = 300; // 5 minutes

    private final AuthAPI authAPI;
    private final Auth0Properties properties;

    /**
     * Cached access token.
     */
    private volatile String cachedToken;

    /**
     * Token expiry timestamp (Instant).
     */
    private volatile Instant tokenExpiry;

    /**
     * Lock for thread-safe token refresh.
     */
    private final Lock tokenLock = new ReentrantLock();

    public Auth0TokenProvider(Auth0Properties properties) {
        this.properties = properties;
        this.authAPI = AuthAPI.newBuilder(
                properties.domain(),
                properties.management().clientId(),
                properties.management().clientSecret()
        ).build();

        logger.info("Auth0TokenProvider initialized for domain: {}", properties.domain());
    }

    /**
     * Get a valid Management API access token.
     * <p>
     * Returns a cached token if available and not expired.
     * Otherwise, requests a new token from Auth0 and caches it.
     * </p>
     *
     * @return valid access token
     * @throws Auth0Exception if token request fails
     */
    public String getAccessToken() throws Auth0Exception {
        // Fast path: token exists and is not expired
        if (isTokenValid()) {
            logger.debug("Using cached Auth0 Management API token");
            return cachedToken;
        }

        // Slow path: acquire lock and refresh token
        tokenLock.lock();
        try {
            // Double-check: another thread might have refreshed the token
            if (isTokenValid()) {
                logger.debug("Token was refreshed by another thread");
                return cachedToken;
            }

            logger.info("Requesting new Auth0 Management API token");
            refreshToken();
            return cachedToken;

        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Force refresh the access token (useful for testing or explicit refresh).
     *
     * @throws Auth0Exception if token request fails
     */
    public void refreshToken() throws Auth0Exception {
        tokenLock.lock();
        try {
            TokenRequest request = authAPI.requestToken(properties.management().audience());
            TokenHolder tokenHolder = request.execute().getBody();

            if (tokenHolder == null || tokenHolder.getAccessToken() == null) {
                throw new Auth0Exception("Token response was null or missing access_token");
            }

            this.cachedToken = tokenHolder.getAccessToken();

            // Calculate expiry: current time + expires_in - buffer
            long expiresIn = tokenHolder.getExpiresIn() != null
                    ? tokenHolder.getExpiresIn()
                    : 86400; // Default 24 hours

            this.tokenExpiry = Instant.now()
                    .plus(expiresIn, ChronoUnit.SECONDS)
                    .minus(EXPIRY_BUFFER_SECONDS, ChronoUnit.SECONDS);

            logger.info("Auth0 Management API token refreshed successfully. Expires at: {}", tokenExpiry);

        } catch (Auth0Exception e) {
            logger.error("Failed to refresh Auth0 Management API token: {}", e.getMessage(), e);
            throw e;
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Check if the cached token is valid (exists and not expired).
     *
     * @return true if token is valid
     */
    private boolean isTokenValid() {
        return cachedToken != null
                && tokenExpiry != null
                && Instant.now().isBefore(tokenExpiry);
    }

    /**
     * Clear the cached token (useful for testing).
     */
    public void clearCache() {
        tokenLock.lock();
        try {
            this.cachedToken = null;
            this.tokenExpiry = null;
            logger.debug("Auth0 token cache cleared");
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Get the current token expiry time (for monitoring/debugging).
     *
     * @return token expiry Instant, or null if no token cached
     */
    public Instant getTokenExpiry() {
        return tokenExpiry;
    }
}
