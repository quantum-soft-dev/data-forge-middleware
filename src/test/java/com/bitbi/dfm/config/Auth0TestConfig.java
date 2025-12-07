package com.bitbi.dfm.config;

import com.auth0.client.mgmt.ManagementAPI;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Auth0 test configuration for integration tests.
 * <p>
 * Provides AccountSyncService bean for test profile that uses mocked ManagementAPI.
 * This allows integration tests to verify the complete two-phase commit workflow
 * without requiring a real Auth0 instance.
 * </p>
 * <p>
 * The ManagementAPI is mocked in individual tests using @MockitoBean.
 * This configuration simply wires the service with the mocked dependency.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@TestConfiguration
@Profile("test")
public class Auth0TestConfig {

    /**
     * Provides AccountSyncService bean for test profile.
     * <p>
     * Uses mocked ManagementAPI (provided by tests via @MockitoBean)
     * and real repository/event publisher beans from Spring context.
     * </p>
     * <p>
     * @Primary annotation ensures this bean overrides the @MockBean from TestSecurityConfig
     * when Auth0TestConfig is imported.
     * </p>
     *
     * @param managementAPI Mocked Auth0 Management API
     * @param accountRepository Real account repository
     * @param eventPublisher Real event publisher
     * @param auth0ManagementApiClient Mocked Auth0 Management API client
     * @return AccountSyncService instance for testing
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public AccountSyncService accountSyncService(
        ManagementAPI managementAPI,
        AccountRepository accountRepository,
        ApplicationEventPublisher eventPublisher,
        Auth0ManagementApiClient auth0ManagementApiClient
    ) {
        return new AccountSyncService(managementAPI, accountRepository, eventPublisher, auth0ManagementApiClient);
    }
}
