package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for integration tests with MockMvc support.
 * <p>
 * Extends AbstractIntegrationTest to inherit Testcontainers configuration.
 * Provides common utilities for HTTP integration tests:
 * - MockMvc for HTTP request testing
 * - Test data loaded from test-data.sql
 * - JWT token generation helpers
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@AutoConfigureMockMvc
@Sql("/test-data.sql")
public abstract class BaseIntegrationTest extends AbstractIntegrationTest {

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
