package com.bitbi.dfm.shared.config;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * KeycloakAdminConfig configures the Keycloak Admin Client for managing users.
 * <p>
 * This configuration uses the CLIENT_CREDENTIALS grant type with a service account
 * that has the necessary roles (manage-users, view-users) in the realm.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakAdminConfig {

    private String authServerUrl;
    private String realm;
    private Admin admin;

    public static class Admin {
        private String clientId;
        private String clientSecret;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    public String getAuthServerUrl() {
        return authServerUrl;
    }

    public void setAuthServerUrl(String authServerUrl) {
        this.authServerUrl = authServerUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /**
     * Creates Keycloak SDK client bean for user management operations.
     * <p>
     * Uses CLIENT_CREDENTIALS grant type with service account authentication.
     * The service account must have 'manage-users' role from realm-management client.
     * </p>
     * <p>
     * Bean name is "keycloak" to avoid conflict with KeycloakAdminClient component.
     * </p>
     *
     * @return configured Keycloak SDK client
     */
    @Bean(name = "keycloak")
    public Keycloak keycloak() {
        return KeycloakBuilder.builder()
                .serverUrl(authServerUrl)
                .realm(realm)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(admin.getClientId())
                .clientSecret(admin.getClientSecret())
                .build();
    }
}
