package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.UUID;

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

    @Autowired
    private S3CheckpointStorage egressCleanupStorage;

    @Autowired
    private S3Client egressCleanupS3Client;

    @Value("${s3.bucket.name}")
    private String egressCleanupBucket;

    /**
     * Delete every delta Parquet under a site's egress prefix. The LocalStack bucket is shared
     * across the whole suite, so any test that uploads (or asserts on) {@code egress/{siteId}/...}
     * must purge in {@code @BeforeEach} — otherwise assertions pass or fail depending on what
     * other test classes left behind (review r3: DeltaEgressWorkerIntegrationTest could go
     * false-green on another test's leftover file).
     *
     * @param siteId site whose egress prefix to purge
     */
    protected void purgeEgressPrefix(UUID siteId) {
        for (String key : egressCleanupStorage.listKeys("egress/" + siteId + "/")) {
            egressCleanupS3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(egressCleanupBucket).key(key).build());
        }
    }
}
