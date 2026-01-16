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
 * <b>CI Environment</b>: In CI (GitHub Actions), external services are provided
 * via workflow services. The manager detects this and skips Testcontainers startup.
 * </p>
 * <p>
 * All integration tests should extend this class to inherit Testcontainers setup.
 * </p>
 *
 * @author Data Forge Team
 * @version 2.1.0
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
     * May be null if using external services (CI environment).
     */
    protected static final PostgreSQLContainer<?> postgresContainer = containersManager.getPostgresContainer();

    /**
     * Redis container reference from singleton manager.
     * May be null if using external services (CI environment).
     */
    protected static final GenericContainer<?> redisContainer = containersManager.getRedisContainer();

    /**
     * LocalStack container reference from singleton manager.
     * May be null if using external services (CI environment).
     */
    protected static final LocalStackContainer localStackContainer = containersManager.getLocalStackContainer();

    /**
     * Configure Spring properties dynamically from Testcontainers or external services.
     * <p>
     * This method is called BEFORE Spring context initialization, allowing
     * Spring Boot to use the dynamically allocated ports and connection strings
     * from Testcontainers or external services (in CI environment).
     * </p>
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        if (containersManager.isUsingExternalServices()) {
            // CI environment - use external services at localhost
            configureExternalServices(registry);
        } else {
            // Local environment - use Testcontainers
            configureTestcontainers(registry);
        }

        // Common configuration
        registry.add("auth0.enabled", () -> "false");
    }

    /**
     * Configure properties for CI environment with external services.
     */
    private static void configureExternalServices(DynamicPropertyRegistry registry) {
        // PostgreSQL configuration (from CI workflow services)
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/dataforge_test");
        registry.add("spring.datasource.username", () -> "dataforge");
        registry.add("spring.datasource.password", () -> "dataforge_test_password");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Redis configuration (from CI workflow services)
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6379);
        registry.add("spring.data.redis.password", () -> ""); // CI Redis has no password

        // S3 / LocalStack configuration (from CI workflow services)
        registry.add("s3.endpoint", () -> "http://localhost:4566");
        registry.add("s3.region", () -> "us-east-1");
        registry.add("s3.access-key", () -> "test");
        registry.add("s3.secret-key", () -> "test");
        registry.add("s3.bucket.name", () -> "data-forge-test-bucket");

        // AWS SDK configuration (alternative property names)
        registry.add("aws.s3.endpoint", () -> "http://localhost:4566");
        registry.add("aws.s3.region", () -> "us-east-1");
        registry.add("aws.accessKeyId", () -> "test");
        registry.add("aws.secretAccessKey", () -> "test");
    }

    /**
     * Configure properties for local environment with Testcontainers.
     */
    private static void configureTestcontainers(DynamicPropertyRegistry registry) {
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
        registry.add("s3.bucket.name", () -> "data-forge-test-bucket");

        // AWS SDK configuration (alternative property names)
        registry.add("aws.s3.endpoint", () -> localStackContainer.getEndpointOverride(S3).toString());
        registry.add("aws.s3.region", localStackContainer::getRegion);
        registry.add("aws.accessKeyId", localStackContainer::getAccessKey);
        registry.add("aws.secretAccessKey", localStackContainer::getSecretKey);
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
