package com.bitbi.dfm.auth.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Service for refresh token management.
 * <p>
 * Handles generation, validation, rotation, and revocation of refresh tokens.
 * Refresh tokens are opaque strings stored as SHA-256 hashes in the database.
 * Token rotation is enforced: on each use, the old token is revoked and a new one issued.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class RefreshTokenService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32; // 256-bit token

    private final RefreshTokenRepository refreshTokenRepository;
    private final SiteRepository siteRepository;
    private final AccountRepository accountRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            SiteRepository siteRepository,
            AccountRepository accountRepository,
            JwtTokenProvider jwtTokenProvider) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.siteRepository = siteRepository;
        this.accountRepository = accountRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Generate a new refresh token for a site.
     *
     * @param siteId site identifier
     * @return the opaque refresh token string (to be returned to client once)
     */
    public String generateRefreshToken(UUID siteId) {
        return issueRefreshToken(siteId).token();
    }

    /**
     * Issue a new refresh token for a site, returning the opaque token and its stored expiry.
     * <p>
     * Callers that report the expiry to clients must use this method: the TTL belongs to the
     * persisted token, and recomputing it from the current clock silently drifts apart from
     * the stored value the moment the two are derived differently.
     * </p>
     * <p>
     * Starts a new rotation family - this is primary issuance (one Device Authorization Flow
     * run). Issuing does not revoke anything by itself; the device flow separately revokes the
     * site's previous tokens, so in practice the new family replaces the old one.
     * </p>
     *
     * @param siteId site identifier
     * @return the opaque refresh token together with the expiry that was persisted
     */
    public IssuedRefreshToken issueRefreshToken(UUID siteId) {
        String opaqueToken = generateOpaqueToken();
        RefreshToken saved = refreshTokenRepository.save(
                RefreshToken.createNewFamily(siteId, sha256(opaqueToken)));

        logger.info("Generated refresh token for site: siteId={}, familyId={}", siteId, saved.getFamilyId());
        return new IssuedRefreshToken(opaqueToken, saved.getExpiresAt());
    }

    /**
     * Issue the successor of a refresh token inside the same rotation family.
     *
     * @param parent the token being rotated out
     * @return the new opaque token together with the expiry that was persisted
     */
    private IssuedRefreshToken rotateRefreshToken(RefreshToken parent) {
        String opaqueToken = generateOpaqueToken();
        RefreshToken saved = refreshTokenRepository.save(
                RefreshToken.rotateFrom(parent, sha256(opaqueToken)));

        logger.debug("Rotated refresh token: siteId={}, familyId={}", saved.getSiteId(), saved.getFamilyId());
        return new IssuedRefreshToken(opaqueToken, saved.getExpiresAt());
    }

    /**
     * Refresh an access token using a refresh token.
     * <p>
     * Validates the refresh token, generates a new JWT access token,
     * and rotates the refresh token (old revoked, new issued).
     * </p>
     *
     * @param refreshTokenString the opaque refresh token
     * @return refresh result containing new access token and rotated refresh token
     * @throws RefreshTokenExpiredException if token is expired
     * @throws RefreshTokenRevokedException if token was already revoked (triggers family revocation)
     * @throws InvalidRefreshTokenException if token is not found
     */
    @Transactional(noRollbackFor = RefreshTokenRevokedException.class)
    public RefreshResult refreshAccessToken(String refreshTokenString) {
        String tokenHash = sha256(refreshTokenString);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            // Refresh token reuse detection (RFC 6819 §5.2.2.3, OAuth 2.0 Security BCP §4.14.2):
            // rotation already consumed this token, so a second presentation means the token leaked.
            // We cannot tell the attacker from the legitimate device, so the compromised chain dies
            // and that device must re-run the Device Authorization Flow.
            //
            // Scope is the rotation family, NOT the site. A site accumulates the revoked tokens of
            // all its past sessions - every rotation revokes one, and re-authorization revokes the
            // previous session wholesale - so revoking site-wide here would turn any of those long
            // dead tokens into a repeatable denial-of-service against the session that is live now.
            // (This scopes reuse detection only; it does not make sessions run in parallel.)
            //
            // NOTE: the revocation must survive the exception, hence noRollbackFor on this method -
            // otherwise the surrounding transaction would roll the family revocation back.
            UUID compromisedFamilyId = refreshToken.getFamilyId();
            int revoked = refreshTokenRepository.revokeAllByFamilyId(compromisedFamilyId);
            logger.warn("Refresh token reuse detected - revoked {} token(s) of rotation family: siteId={}, familyId={}",
                    revoked, refreshToken.getSiteId(), compromisedFamilyId);
            throw new RefreshTokenRevokedException("Refresh token has been revoked");
        }

        if (refreshToken.isExpired()) {
            logger.warn("Attempted to use expired refresh token: siteId={}", refreshToken.getSiteId());
            throw new RefreshTokenExpiredException("Refresh token has expired");
        }

        UUID siteId = refreshToken.getSiteId();

        // Validate site still exists and is active
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new InvalidRefreshTokenException("Site not found"));

        if (!site.getIsActive()) {
            throw new InvalidRefreshTokenException("Site is not active");
        }

        // Validate parent account is active
        Account account = accountRepository.findById(site.getAccountId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Account not found"));

        if (!account.getIsActive()) {
            throw new InvalidRefreshTokenException("Account is not active");
        }

        // Revoke old refresh token (rotation)
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        // Generate new JWT access token
        JwtToken accessToken = jwtTokenProvider.generateToken(siteId, site.getAccountId());

        // Generate new refresh token (rotation) - stays in the presented token's family
        IssuedRefreshToken newRefreshToken = rotateRefreshToken(refreshToken);

        logger.info("Access token refreshed successfully: siteId={}", siteId);

        return new RefreshResult(
                accessToken,
                newRefreshToken.token(),
                newRefreshToken.expiresAt()
        );
    }

    /**
     * Revoke a specific refresh token.
     *
     * @param refreshTokenString the opaque refresh token to revoke
     */
    public void revokeRefreshToken(String refreshTokenString) {
        String tokenHash = sha256(refreshTokenString);

        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
            logger.info("Refresh token revoked: siteId={}", token.getSiteId());
        });
    }

    /**
     * Revoke all refresh tokens for a site.
     *
     * @param siteId site identifier
     */
    public void revokeAllForSite(UUID siteId) {
        int revoked = refreshTokenRepository.revokeAllBySiteId(siteId);
        logger.info("Revoked {} refresh tokens for site: siteId={}", revoked, siteId);
    }

    /**
     * Cleanup expired and revoked tokens.
     * Runs daily.
     */
    @Scheduled(fixedDelay = 86400000, initialDelay = 3600000) // 24h between runs, 1h initial delay
    @Transactional
    public void cleanupExpiredTokens() {
        logger.debug("Running refresh token cleanup...");
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            logger.info("Cleaned up {} expired/revoked refresh tokens", deleted);
        }
    }

    // --- Private helpers ---

    private String generateOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // --- Result types ---

    /**
     * A freshly issued refresh token and the expiry persisted with it.
     *
     * @param token     opaque refresh token (returned to the client once)
     * @param expiresAt expiry as stored on the token entity
     */
    public record IssuedRefreshToken(String token, Instant expiresAt) {
    }

    /**
     * Result of a successful token refresh.
     */
    public record RefreshResult(
            JwtToken accessToken,
            String refreshToken,
            Instant refreshTokenExpiresAt
    ) {
    }

    // --- Exceptions ---

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    public static class RefreshTokenExpiredException extends RuntimeException {
        public RefreshTokenExpiredException(String message) {
            super(message);
        }
    }

    public static class RefreshTokenRevokedException extends RuntimeException {
        public RefreshTokenRevokedException(String message) {
            super(message);
        }
    }
}
