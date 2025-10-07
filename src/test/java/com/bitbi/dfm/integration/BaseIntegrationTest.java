package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.config.TestS3Config;
import com.bitbi.dfm.config.TestSecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for integration tests.
 * <p>
 * Provides common configuration and utilities for integration tests:
 * - Test security configuration with mock OAuth2
 * - Test S3 configuration with LocalStack
 * - Test data loaded from test-data.sql
 * - JWT token generation helper
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, TestS3Config.class})
@Sql("/test-data.sql")
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected TokenService tokenService;

    /**
     * Generate JWT token for test site.
     * <p>
     * Default site: store-01.example.com (the site that owns test batches)
     * Site ID: 0199baac-f852-753f-6fc3-7c994fc38654
     * </p>
     *
     * @return Bearer token string
     */
    protected String generateTestToken() {
        return generateToken("store-01.example.com", "valid-secret-uuid");
    }

    /**
     * Generate JWT token for specific site.
     *
     * @param domain       site domain
     * @param clientSecret site client secret
     * @return Bearer token string
     */
    protected String generateToken(String domain, String clientSecret) {
        JwtToken token = tokenService.generateToken(domain, clientSecret);
        return "Bearer " + token.token();
    }
}
