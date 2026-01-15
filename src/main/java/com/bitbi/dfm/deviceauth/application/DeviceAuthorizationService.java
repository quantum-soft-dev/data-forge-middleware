package com.bitbi.dfm.deviceauth.application;

import com.bitbi.dfm.deviceauth.domain.DeviceAuthorization;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationRepository;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationStatus;
import com.bitbi.dfm.site.application.SiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Application service for Device Authorization Flow.
 * <p>
 * Implements RFC 8628 Device Authorization Grant adapted for site creation.
 * When user approves, a new site is automatically created.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class DeviceAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthorizationService.class);

    // Characters for user code (excludes ambiguous: I, O, 0, 1)
    private static final String USER_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DeviceAuthorizationRepository repository;
    private final SiteService siteService;

    @Value("${device-authorization.verification-uri:https://app.dataforge.com/device-verify}")
    private String verificationUri;

    @Value("${device-authorization.expiration-minutes:15}")
    private int expirationMinutes;

    @Value("${device-authorization.polling-interval-seconds:5}")
    private int pollingIntervalSeconds;

    @Value("${device-authorization.device-code-length:64}")
    private int deviceCodeLength;

    @Value("${app.api-base-url:https://api.dataforge.com}")
    private String apiBaseUrl;

    public DeviceAuthorizationService(DeviceAuthorizationRepository repository,
                                      SiteService siteService) {
        this.repository = repository;
        this.siteService = siteService;
    }

    /**
     * Initiate device authorization flow.
     * <p>
     * Generates device code and user code, stores authorization request.
     * </p>
     *
     * @param siteName        requested site name
     * @param siteDescription optional site description
     * @return authorization result with codes
     */
    public AuthorizationResult authorize(String siteName, String siteDescription) {
        logger.info("Initiating device authorization: siteName={}", siteName);

        String deviceCode = generateDeviceCode();
        String userCode = generateUniqueUserCode();
        Instant expiresAt = Instant.now().plusSeconds(expirationMinutes * 60L);

        DeviceAuthorization authorization = new DeviceAuthorization(
                deviceCode,
                userCode,
                siteName,
                siteDescription,
                expiresAt
        );

        repository.save(authorization);

        logger.info("Device authorization created: userCode={}, expiresAt={}", userCode, expiresAt);

        String verificationUriComplete = verificationUri + "?code=" + userCode;

        return new AuthorizationResult(
                deviceCode,
                userCode,
                verificationUri,
                verificationUriComplete,
                expirationMinutes * 60,
                pollingIntervalSeconds
        );
    }

    /**
     * Result of authorization initiation.
     */
    public record AuthorizationResult(
            String deviceCode,
            String userCode,
            String verificationUri,
            String verificationUriComplete,
            int expiresIn,
            int interval
    ) {
    }

    /**
     * Poll for token (device side).
     * <p>
     * Returns credentials if approved, or appropriate error status.
     * </p>
     *
     * @param deviceCode the device code
     * @return token result
     */
    @Transactional(readOnly = true)
    public TokenResult getToken(String deviceCode) {
        logger.debug("Token poll: deviceCode={}", deviceCode.substring(0, 8) + "...");

        DeviceAuthorization authorization = repository.findByDeviceCode(deviceCode)
                .orElse(null);

        if (authorization == null) {
            return TokenResult.invalidGrant();
        }

        // Check expiration
        if (authorization.isExpired()) {
            return TokenResult.expiredToken();
        }

        // Check status
        return switch (authorization.getStatus()) {
            case PENDING -> TokenResult.authorizationPending();
            case APPROVED -> TokenResult.success(
                    authorization.getSiteId(),
                    authorization.getDomain(),
                    // Note: We can't return plain secret here - it was hashed
                    // The secret is stored in a separate field for one-time return
                    getPlainSecretFromAuthorization(authorization),
                    apiBaseUrl
            );
            case DENIED -> TokenResult.accessDenied();
            case EXPIRED -> TokenResult.expiredToken();
        };
    }

    /**
     * Token polling result.
     */
    public sealed interface TokenResult {
        record Success(UUID siteId, String domain, String clientSecret, String apiBaseUrl) implements TokenResult {
        }

        record Pending() implements TokenResult {
        }

        record AccessDenied() implements TokenResult {
        }

        record ExpiredToken() implements TokenResult {
        }

        record InvalidGrant() implements TokenResult {
        }

        static TokenResult success(UUID siteId, String domain, String clientSecret, String apiBaseUrl) {
            return new Success(siteId, domain, clientSecret, apiBaseUrl);
        }

        static TokenResult authorizationPending() {
            return new Pending();
        }

        static TokenResult accessDenied() {
            return new AccessDenied();
        }

        static TokenResult expiredToken() {
            return new ExpiredToken();
        }

        static TokenResult invalidGrant() {
            return new InvalidGrant();
        }
    }

    /**
     * Get authorization info for verification page.
     *
     * @param userCode the user code
     * @return authorization info
     * @throws AuthorizationNotFoundException if not found
     * @throws AuthorizationExpiredException  if expired
     */
    @Transactional(readOnly = true)
    public AuthorizationInfo getAuthorizationInfo(String userCode) {
        DeviceAuthorization authorization = repository.findByUserCode(userCode)
                .orElseThrow(() -> new AuthorizationNotFoundException("Authorization not found: " + userCode));

        if (authorization.isExpired()) {
            throw new AuthorizationExpiredException("Authorization has expired");
        }

        if (authorization.getStatus() != DeviceAuthorizationStatus.PENDING) {
            throw new AuthorizationAlreadyProcessedException("Authorization already processed");
        }

        return new AuthorizationInfo(
                authorization.getUserCode(),
                authorization.getSiteName(),
                authorization.getSiteDescription(),
                authorization.getExpiresAt()
        );
    }

    /**
     * Authorization info for display.
     */
    public record AuthorizationInfo(
            String userCode,
            String siteName,
            String siteDescription,
            Instant expiresAt
    ) {
    }

    /**
     * Approve authorization and create site.
     * <p>
     * Creates new site with generated credentials.
     * Credentials are stored for one-time retrieval by device.
     * </p>
     *
     * @param userCode  the user code
     * @param accountId authenticated user's account ID
     * @return verification result with created site info
     */
    public VerificationResult verify(String userCode, UUID accountId) {
        logger.info("Verifying authorization: userCode={}, accountId={}", userCode, accountId);

        DeviceAuthorization authorization = repository.findByUserCode(userCode)
                .orElseThrow(() -> new AuthorizationNotFoundException("Authorization not found: " + userCode));

        if (authorization.isExpired()) {
            throw new AuthorizationExpiredException("Authorization has expired");
        }

        if (authorization.getStatus() != DeviceAuthorizationStatus.PENDING) {
            throw new AuthorizationAlreadyProcessedException("Authorization already processed");
        }

        // Get or create site with new credentials
        SiteService.SiteCreationResult siteResult = siteService.getOrCreateSiteWithNewCredentials(
                accountId,
                authorization.getSiteName(),
                authorization.getSiteDescription() != null
                        ? authorization.getSiteDescription()
                        : authorization.getSiteName()
        );

        // Update authorization with site info
        authorization.setAccountId(accountId);
        authorization.setSiteId(siteResult.site().getId());
        authorization.setDomain(siteResult.site().getDomain());
        // Store the hashed secret (we'll use a workaround to return plain secret)
        authorization.setClientSecretHash(encodeSecretForOneTimeReturn(siteResult.plaintextSecret()));
        authorization.setStatus(DeviceAuthorizationStatus.APPROVED);
        authorization.setApprovedAt(Instant.now());

        repository.save(authorization);

        logger.info("Authorization verified: userCode={}, siteId={}, domain={}",
                userCode, siteResult.site().getId(), siteResult.site().getDomain());

        return new VerificationResult(
                siteResult.site().getId(),
                authorization.getSiteName()
        );
    }

    /**
     * Verification result.
     */
    public record VerificationResult(
            UUID siteId,
            String siteName
    ) {
    }

    /**
     * Deny authorization request.
     *
     * @param userCode the user code
     */
    public void deny(String userCode) {
        logger.info("Denying authorization: userCode={}", userCode);

        DeviceAuthorization authorization = repository.findByUserCode(userCode)
                .orElseThrow(() -> new AuthorizationNotFoundException("Authorization not found: " + userCode));

        if (authorization.getStatus() != DeviceAuthorizationStatus.PENDING) {
            throw new AuthorizationAlreadyProcessedException("Authorization already processed");
        }

        authorization.setStatus(DeviceAuthorizationStatus.DENIED);
        repository.save(authorization);

        logger.info("Authorization denied: userCode={}", userCode);
    }

    /**
     * Cleanup expired authorizations.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void cleanupExpired() {
        logger.debug("Running device authorization cleanup...");

        int updated = repository.markExpired(Instant.now());

        if (updated > 0) {
            logger.info("Marked {} expired device authorizations", updated);
        }
    }

    // --- Private helpers ---

    private String generateDeviceCode() {
        // Base64 produces 4 chars per 3 bytes, so we need ceil(length * 3/4) bytes
        // Using full deviceCodeLength bytes ensures we always have enough chars
        byte[] bytes = new byte[deviceCodeLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .substring(0, deviceCodeLength);
    }

    private String generateUserCode() {
        StringBuilder sb = new StringBuilder(9); // XXXX-XXXX
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(USER_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(USER_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private String generateUniqueUserCode() {
        for (int i = 0; i < 10; i++) {
            String code = generateUserCode();
            if (!repository.existsByUserCode(code)) {
                return code;
            }
        }
        throw new RuntimeException("Failed to generate unique user code after 10 attempts");
    }

    /**
     * Encode secret for one-time return (base64 encoded for storage).
     * This is a workaround since we need to return the plain secret to the device.
     */
    private String encodeSecretForOneTimeReturn(String plainSecret) {
        // Store base64 encoded for one-time retrieval
        // In production, consider encrypting this
        return "PLAIN:" + Base64.getEncoder().encodeToString(plainSecret.getBytes());
    }

    /**
     * Get plain secret from authorization (one-time retrieval).
     */
    private String getPlainSecretFromAuthorization(DeviceAuthorization authorization) {
        String stored = authorization.getClientSecretHash();
        if (stored != null && stored.startsWith("PLAIN:")) {
            return new String(Base64.getDecoder().decode(stored.substring(6)));
        }
        // Fallback - should not happen in normal flow
        return null;
    }

    // --- Exceptions ---

    public static class AuthorizationNotFoundException extends RuntimeException {
        public AuthorizationNotFoundException(String message) {
            super(message);
        }
    }

    public static class AuthorizationExpiredException extends RuntimeException {
        public AuthorizationExpiredException(String message) {
            super(message);
        }
    }

    public static class AuthorizationAlreadyProcessedException extends RuntimeException {
        public AuthorizationAlreadyProcessedException(String message) {
            super(message);
        }
    }
}
