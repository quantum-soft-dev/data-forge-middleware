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
        private String username;
        private String password;

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

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
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
     * Uses PASSWORD grant type with admin username/password authentication.
     * Falls back to CLIENT_CREDENTIALS if username/password not provided.
     * </p>
     * <p>
     * Bean name is "keycloak" to avoid conflict with KeycloakAdminClient component.
     * </p>
     *
     * @return configured Keycloak SDK client
     */
    @Bean(name = "keycloak")
    public Keycloak keycloak() {
        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(authServerUrl)
                .realm("master") // Always authenticate against master realm for admin operations
                .clientId(admin.getClientId());

        // Use PASSWORD grant if username/password provided, otherwise CLIENT_CREDENTIALS
        if (admin.getUsername() != null && !admin.getUsername().isBlank()) {
            builder.grantType(OAuth2Constants.PASSWORD)
                   .username(admin.getUsername())
                   .password(admin.getPassword());
        } else {
            builder.grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                   .clientSecret(admin.getClientSecret());
        }

        return builder.build();
    }
}
