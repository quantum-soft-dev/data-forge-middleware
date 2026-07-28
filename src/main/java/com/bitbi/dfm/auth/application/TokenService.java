package com.bitbi.dfm.auth.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.domain.SiteAuthenticationRepository;
import com.bitbi.dfm.auth.domain.SiteAuthenticationStatus;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for token operations.
 * <p>
 * Handles JWT token generation and validation with site authentication.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private final SiteRepository siteRepository;
    private final AccountRepository accountRepository;
    private final SiteAuthenticationRepository siteAuthenticationRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public TokenService(SiteRepository siteRepository,
                        AccountRepository accountRepository,
                        SiteAuthenticationRepository siteAuthenticationRepository,
                        JwtTokenProvider jwtTokenProvider) {
        this.siteRepository = siteRepository;
        this.accountRepository = accountRepository;
        this.siteAuthenticationRepository = siteAuthenticationRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Generate JWT token for a site (Auth V2 - by siteId, no credentials required).
     * <p>
     * Used internally after successful authentication (e.g., Device Flow approval).
     * </p>
     *
     * @param siteId site identifier
     * @return JWT token with site claims
     * @throws AuthenticationException if site is invalid or inactive
     */
    @Transactional(readOnly = true)
    public JwtToken generateTokenForSite(UUID siteId) {
        logger.debug("Generating token for siteId: {}", siteId);

        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        if (!site.getIsActive()) {
            logger.warn("Site is not active: siteId={}", siteId);
            throw new AuthenticationException("Invalid credentials");
        }

        Account account = accountRepository.findById(site.getAccountId())
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        if (!account.getIsActive()) {
            logger.warn("Parent account is not active: accountId={}, siteId={}", account.getId(), siteId);
            throw new AuthenticationException("Invalid credentials");
        }

        JwtToken token = jwtTokenProvider.generateToken(site.getId(), site.getAccountId());

        logger.info("Token generated successfully: siteId={}", siteId);
        return token;
    }

    /**
     * Validate JWT token.
     * <p>
     * Re-checks the current state of the site <em>and</em> its parent account on every call:
     * a JWT only states what was true when it was issued, so deactivating an account or a site
     * must take effect immediately rather than when the access token happens to expire.
     * Both flags come from a single joined query - this runs on every client API request.
     * </p>
     *
     * @param tokenString JWT token string
     * @return site ID from token
     * @throws InvalidTokenException if token is invalid or expired, or the site/account is not active
     */
    @Transactional(readOnly = true)
    public UUID validateToken(String tokenString) {
        try {
            UUID siteId = jwtTokenProvider.extractSiteId(tokenString);

            SiteAuthenticationStatus status = siteAuthenticationRepository.findAuthenticationStatus(siteId)
                    .orElseThrow(() -> new InvalidTokenException("Site not found"));

            if (!status.siteActive()) {
                throw new InvalidTokenException("Site is not active");
            }

            if (!status.accountActive()) {
                logger.warn("Parent account is not active: accountId={}, siteId={}", status.accountId(), siteId);
                throw new InvalidTokenException("Account is not active");
            }

            return siteId;

        } catch (JwtTokenProvider.InvalidTokenException e) {
            throw new InvalidTokenException("Invalid or expired token", e);
        }
    }

    /**
     * Extract site ID from token without full validation.
     *
     * @param tokenString JWT token string
     * @return site ID
     * @throws InvalidTokenException if token is malformed
     */
    public UUID extractSiteId(String tokenString) {
        try {
            return jwtTokenProvider.extractSiteId(tokenString);
        } catch (JwtTokenProvider.InvalidTokenException e) {
            throw new InvalidTokenException("Invalid token format", e);
        }
    }

    /**
     * Extract account ID from token without full validation.
     *
     * @param tokenString JWT token string
     * @return account ID
     * @throws InvalidTokenException if token is malformed
     */
    public UUID extractAccountId(String tokenString) {
        try {
            return jwtTokenProvider.extractAccountId(tokenString);
        } catch (JwtTokenProvider.InvalidTokenException e) {
            throw new InvalidTokenException("Invalid token format", e);
        }
    }

    /**
     * Exception thrown when authentication fails.
     */
    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when token is invalid.
     */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }

        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
