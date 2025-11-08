package com.bitbi.dfm.integration;

import com.bitbi.dfm.config.Auth0TestConfig;
import com.bitbi.dfm.config.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

/**
 * Base class for integration tests with Testcontainers singleton pattern.
 * <p>
 * Provides shared Testcontainers configuration:
 * - PostgreSQL 16 for database operations
 * - Redis 7 for caching
 * - LocalStack for S3 operations
 * </p>
 * <p>
 * <b>Singleton Pattern</b>: Uses {@link TestContainersManager} to ensure containers
 * are started only once across all test classes, preventing race conditions during
 * parallel test execution.
 * </p>
 * <p>
 * All integration tests should extend this class to inherit Testcontainers setup.
 * </p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 * @since Phase 11 - Testcontainers Singleton Configuration
 */
@SpringBootTest(properties = {
    "auth0.enabled=false",  // Disable Auth0 for integration tests (use mocks)
    "spring.flyway.enabled=true",  // Enable Flyway migrations
    "spring.jpa.hibernate.ddl-auto=none"  // Use Flyway for schema management
})
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, Auth0TestConfig.class})
public abstract class AbstractIntegrationTest {

    /**
     * Singleton manager for all Testcontainers.
     * Ensures containers are started only once across all test classes.
     */
    protected static final TestContainersManager containersManager = TestContainersManager.getInstance();

    /**
     * PostgreSQL container reference from singleton manager.
     */
    protected static final PostgreSQLContainer<?> postgresContainer = containersManager.getPostgresContainer();

    /**
     * Redis container reference from singleton manager.
     */
    protected static final GenericContainer<?> redisContainer = containersManager.getRedisContainer();

    /**
     * LocalStack container reference from singleton manager.
     */
    protected static final LocalStackContainer localStackContainer = containersManager.getLocalStackContainer();

    /**
     * Configure Spring properties dynamically from Testcontainers.
     * <p>
     * This method is called BEFORE Spring context initialization, allowing
     * Spring Boot to use the dynamically allocated ports and connection strings
     * from Testcontainers.
     * </p>
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL configuration
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Redis configuration
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test_password");

        // S3 / LocalStack configuration
        registry.add("s3.endpoint", () -> localStackContainer.getEndpointOverride(S3).toString());
        registry.add("s3.region", localStackContainer::getRegion);
        registry.add("s3.access-key", localStackContainer::getAccessKey);
        registry.add("s3.secret-key", localStackContainer::getSecretKey);
        registry.add("s3.bucket.name", () -> "data-forge-test-bucket"); // Match application-test.yml and TestContainersManager

        // AWS SDK configuration (alternative property names)
        registry.add("aws.s3.endpoint", () -> localStackContainer.getEndpointOverride(S3).toString());
        registry.add("aws.s3.region", localStackContainer::getRegion);
        registry.add("aws.accessKeyId", localStackContainer::getAccessKey);
        registry.add("aws.secretAccessKey", localStackContainer::getSecretKey);

        // Disable external services
        registry.add("auth0.enabled", () -> "false");
    }

    /**
     * Verify that all Testcontainers are running.
     * Call this method in tests if you need to ensure containers are up.
     *
     * @return true if all containers are running
     */
    protected boolean areContainersRunning() {
        return containersManager.areAllContainersRunning();
    }

    /**
     * Get PostgreSQL JDBC URL.
     * Useful for debugging connection issues.
     *
     * @return JDBC URL
     */
    protected String getPostgresJdbcUrl() {
        return containersManager.getPostgresJdbcUrl();
    }

    /**
     * Get Redis connection info.
     * Useful for debugging connection issues.
     *
     * @return Redis connection string
     */
    protected String getRedisConnectionInfo() {
        return containersManager.getRedisConnectionInfo();
    }

    /**
     * Get LocalStack S3 endpoint.
     * Useful for debugging S3 operations.
     *
     * @return S3 endpoint URL
     */
    protected String getS3Endpoint() {
        return containersManager.getS3Endpoint();
    }
}
