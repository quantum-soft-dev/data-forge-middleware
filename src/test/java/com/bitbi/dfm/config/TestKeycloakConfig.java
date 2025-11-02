package com.bitbi.dfm.config;

import com.bitbi.dfm.account.application.KeycloakAccountSyncService;
import com.bitbi.dfm.account.infrastructure.KeycloakAdminClient;
import com.bitbi.dfm.shared.config.KeycloakAdminConfig;
import org.keycloak.admin.client.Keycloak;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * Test configuration to provide mocked Keycloak beans for integration tests.
 * <p>
 * This prevents KeycloakAdminConfig from attempting to create a real Keycloak client
 * during tests, which would require a running Keycloak server.
 * </p>
 * <p>
 * Only activated when keycloak.enabled=false in test profile.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@TestConfiguration
@ConditionalOnProperty(name = "keycloak.enabled", havingValue = "false")
public class TestKeycloakConfig {

    /**
     * Provides a mocked Keycloak bean for tests.
     *
     * @return Mocked Keycloak instance
     */
    @Bean(name = "keycloak")
    @Primary
    public Keycloak keycloak() {
        return mock(Keycloak.class);
    }

    /**
     * Provides a mocked KeycloakAdminConfig for tests.
     *
     * @return Mocked KeycloakAdminConfig instance
     */
    @Bean
    @Primary
    public KeycloakAdminConfig keycloakAdminConfig() {
        return mock(KeycloakAdminConfig.class);
    }

    /**
     * Provides a mocked KeycloakAdminClient for tests.
     *
     * @return Mocked KeycloakAdminClient instance
     */
    @Bean
    @Primary
    public KeycloakAdminClient keycloakAdminClient() {
        return mock(KeycloakAdminClient.class);
    }

    /**
     * Provides a mocked KeycloakAccountSyncService for tests.
     *
     * @return Mocked KeycloakAccountSyncService instance
     */
    @Bean
    @Primary
    public KeycloakAccountSyncService keycloakAccountSyncService() {
        return mock(KeycloakAccountSyncService.class);
    }
}
