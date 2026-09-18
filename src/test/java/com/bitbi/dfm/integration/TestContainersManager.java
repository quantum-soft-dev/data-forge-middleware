package com.bitbi.dfm.integration;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;


/**
 * Singleton manager for Testcontainers to prevent race conditions during parallel test execution.
 * <p>
 * This class ensures that:
 * - Containers are started only once across all test classes
 * - All tests wait for containers to be ready before execution
 * - No race conditions from parallel test execution
 * </p>
 * <p>
 * <b>Problem Solved</b>: Previous implementation with static @Container fields in AbstractIntegrationTest
 * caused race conditions when Gradle ran tests in parallel. Tests would start before containers were ready,
 * resulting in java.net.ConnectException failures.
 * </p>
 * <p>
 * <b>Solution</b>: Singleton pattern with static initialization block ensures containers start exactly once
 * before any test class loads, and all subsequent test classes reuse the same running containers.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @since Phase 11 - Testcontainers Singleton Configuration
 */
public class TestContainersManager {

    private static final TestContainersManager INSTANCE = new TestContainersManager();

    /**
     * Flag indicating whether we're using external services (CI environment)
     * instead of Testcontainers.
     */
    private final boolean useExternalServices;

    /**
     * Shutdown hook to stop all containers when JVM exits.
     * Registered automatically during singleton initialization.
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[TestContainersManager] Shutting down containers...");
            TestContainersManager manager = getInstance();

            // Skip shutdown if using external services (CI environment)
            if (manager.useExternalServices) {
                System.out.println("[TestContainersManager] Using external services - no containers to stop.");
                return;
            }

            if (manager.postgresContainer != null && manager.postgresContainer.isRunning()) {
                manager.postgresContainer.stop();
            }
            if (manager.localStackContainer != null && manager.localStackContainer.isRunning()) {
                manager.localStackContainer.stop();
            }

            System.out.println("[TestContainersManager] All containers stopped.");
        }));
    }

    /**
     * PostgreSQL 16 container - singleton shared across ALL tests.
     * May be null if using external services.
     */
    private final PostgreSQLContainer postgresContainer;
    /**
     * LocalStack container for S3 - singleton shared across ALL tests.
     * May be null if using external services.
     */
    private final LocalStackContainer localStackContainer;

    /**
     * Private constructor ensures singleton pattern.
     * Initializes and starts all containers in a thread-safe manner.
     * <p>
     * In CI environment (when services are already available at localhost),
     * skips Testcontainers startup and uses external services.
     * </p>
     */
    private TestContainersManager() {
        // Check if we're in CI environment with external services
        this.useExternalServices = detectExternalServices();

        if (useExternalServices) {
            System.out.println("[TestContainersManager] External services detected (CI environment) - skipping Testcontainers");
            // Don't initialize containers - use external services
            postgresContainer = null;
            localStackContainer = null;
            return;
        }

        System.out.println("[TestContainersManager] No external services detected - starting Testcontainers");

        // Initialize PostgreSQL container
        postgresContainer = new PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine")
        )
                .withDatabaseName("dataforge_test")
                .withUsername("test")
                .withPassword("test");

        // Initialize LocalStack container
        // Using LocalStack 3.x which is compatible with Testcontainers 1.20+
        localStackContainer = new LocalStackContainer(
                DockerImageName.parse("localstack/localstack:3.8")
        )
                .withServices("s3")
                .withStartupTimeout(Duration.ofMinutes(3));

        try {
            Thread.sleep(Duration.ofSeconds(10));
        } catch (InterruptedException ie) {
            System.out.println("Thread interrupted");
        }
        // Start all containers (thread-safe - happens only once)
        startContainers();
    }

    /**
     * Detect if external services are already available (CI environment).
     * Checks if PostgreSQL and LocalStack are reachable at localhost.
     *
     * @return true if all external services are available
     */
    private boolean detectExternalServices() {
        // Check for CI environment variable
        String ciEnv = System.getenv("CI");
        if (!"true".equalsIgnoreCase(ciEnv)) {
            return false;
        }

        System.out.println("[TestContainersManager] CI environment detected, checking for external services...");

        // Check PostgreSQL at localhost:5432
        boolean postgresAvailable = isPortOpen("localhost", 5432);
        System.out.println("[TestContainersManager] PostgreSQL (localhost:5432): " + (postgresAvailable ? "available" : "not available"));

        // Check LocalStack at localhost:4566
        boolean localstackAvailable = isPortOpen("localhost", 4566);
        System.out.println("[TestContainersManager] LocalStack (localhost:4566): " + (localstackAvailable ? "available" : "not available"));

        return postgresAvailable && localstackAvailable;
    }

    /**
     * Check if a port is open on the given host.
     *
     * @param host the hostname
     * @param port the port number
     * @return true if the port is open
     */
    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the singleton instance.
     *
     * @return the singleton TestContainersManager instance
     */
    public static TestContainersManager getInstance() {
        return INSTANCE;
    }

    /**
     * Start all containers and wait for them to be ready.
     * This method is called only once during singleton initialization.
     */
    private void startContainers() {
        System.out.println("[TestContainersManager] Starting containers...");

        // Start PostgreSQL
        if (!postgresContainer.isRunning()) {
            postgresContainer.start();
            System.out.println("[TestContainersManager] PostgreSQL started: " + postgresContainer.getJdbcUrl());
        }

        // Start LocalStack
        if (!localStackContainer.isRunning()) {
            localStackContainer.start();
            System.out.println("[TestContainersManager] LocalStack started: " + localStackContainer.getEndpoint());

            // Create S3 bucket for tests (required for LocalStack)
            createS3TestBucket();
        }

        System.out.println("[TestContainersManager] All containers ready!");
    }

    /**
     * Create S3 test bucket in LocalStack with retry logic.
     * This ensures the bucket exists before any tests run.
     * LocalStack S3 service needs a few seconds to initialize after container starts.
     */
    private void createS3TestBucket() {
        String bucketName = "data-forge-test-bucket"; // Match application-test.yml configuration
        int maxRetries = 5;
        int retryDelayMs = 1000; // 1 second

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Wait a bit before first attempt to let LocalStack initialize
                if (attempt == 1) {
                    Thread.sleep(2000); // Initial 2-second wait
                }

                // Create S3Client configured for LocalStack
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials credentials =
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                localStackContainer.getAccessKey(),
                                localStackContainer.getSecretKey()
                        );

                software.amazon.awssdk.services.s3.S3Client s3Client = software.amazon.awssdk.services.s3.S3Client.builder()
                        .region(software.amazon.awssdk.regions.Region.of(localStackContainer.getRegion()))
                        .endpointOverride(localStackContainer.getEndpoint())
                        .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(credentials))
                        .forcePathStyle(true) // Required for LocalStack
                        .build();

                // Create bucket
                s3Client.createBucket(builder -> builder.bucket(bucketName));
                System.out.println("[TestContainersManager] S3 bucket created: " + bucketName + " (attempt " + attempt + ")");

                s3Client.close();
                return; // Success - exit retry loop
            } catch (Exception e) {
                System.err.println("[TestContainersManager] S3 bucket creation attempt " + attempt + " failed: " + e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        System.out.println("[TestContainersManager] Retrying in " + retryDelayMs + "ms...");
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    System.err.println("[TestContainersManager] Failed to create S3 bucket after " + maxRetries + " attempts");
                    // Don't fail initialization - tests will handle bucket creation if needed
                }
            }
        }
    }

    /**
     * Check if using external services (CI environment).
     *
     * @return true if using external services instead of Testcontainers
     */
    public boolean isUsingExternalServices() {
        return useExternalServices;
    }

    /**
     * Get the PostgreSQL container.
     *
     * @return the running PostgreSQL container, or null if using external services
     */
    public PostgreSQLContainer getPostgresContainer() {
        return postgresContainer;
    }

    /**
     * Get the LocalStack container.
     *
     * @return the running LocalStack container, or null if using external services
     */
    public LocalStackContainer getLocalStackContainer() {
        return localStackContainer;
    }

    /**
     * Verify that all containers/services are running.
     *
     * @return true if all containers are running or external services are available
     */
    public boolean areAllContainersRunning() {
        if (useExternalServices) {
            return detectExternalServices();
        }
        return postgresContainer != null && postgresContainer.isRunning()
                && localStackContainer != null && localStackContainer.isRunning();
    }

    /**
     * Get PostgreSQL JDBC URL for debugging.
     *
     * @return JDBC URL
     */
    public String getPostgresJdbcUrl() {
        if (useExternalServices) {
            return "jdbc:postgresql://localhost:5432/dataforge_test";
        }
        return postgresContainer.getJdbcUrl();
    }

    /**
     * Get LocalStack S3 endpoint for debugging.
     *
     * @return S3 endpoint URL
     */
    public String getS3Endpoint() {
        if (useExternalServices) {
            return "http://localhost:4566";
        }
        return localStackContainer.getEndpoint().toString();
    }
}
