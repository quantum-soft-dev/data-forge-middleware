package com.bitbi.dfm.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates security configuration on application startup.
 * <p>
 * Ensures that critical security settings are properly configured
 * before the application accepts requests.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class SecurityConfigValidator {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfigValidator.class);

    // Default/example secrets that should never be used in production
    private static final String DEFAULT_JWT_SECRET = "your-secret-key-here-change-in-production-minimum-256-bits";
    private static final String EXAMPLE_JWT_SECRET = "change-me";
    private static final String TEST_JWT_SECRET = "test-secret";
    private static final int MINIMUM_SECRET_LENGTH = 32; // 256 bits

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Validate JWT secret configuration on application startup.
     * <p>
     * Fails fast if using default/insecure secret in non-test environments.
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateJwtSecret() {
        logger.info("Validating JWT secret configuration...");

        // Skip validation for test profile
        if ("test".equals(activeProfile)) {
            logger.debug("Skipping JWT secret validation for test profile");
            return;
        }

        // Check if using default secret
        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            String errorMessage = "FATAL: Application is using default JWT secret. " +
                    "This is a critical security vulnerability. " +
                    "Set environment variable JWT_SECRET to a secure random value (minimum 32 characters).";
            logger.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        // Check if using example secret
        if (EXAMPLE_JWT_SECRET.equals(jwtSecret) || TEST_JWT_SECRET.equals(jwtSecret)) {
            String errorMessage = "FATAL: Application is using example/test JWT secret in non-test environment. " +
                    "Set environment variable JWT_SECRET to a secure random value (minimum 32 characters).";
            logger.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        // Check minimum secret length
        if (jwtSecret.length() < MINIMUM_SECRET_LENGTH) {
            String errorMessage = String.format(
                    "FATAL: JWT secret is too short (%d characters). " +
                    "Minimum length is %d characters for HMAC-SHA256 security. " +
                    "Use a longer secret key.",
                    jwtSecret.length(),
                    MINIMUM_SECRET_LENGTH
            );
            logger.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        logger.info("JWT secret validation passed: {} characters", jwtSecret.length());
    }
}
