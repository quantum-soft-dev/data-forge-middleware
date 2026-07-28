package com.bitbi.dfm.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles must come from the Auth0 claim and from nowhere else.
 * <p>
 * The converter used to fall back to Keycloak's {@code realm_access.roles} "for backward
 * compatibility during migration". The migration is over: no Auth0 token carries that claim, so
 * the fallback grants authorities from a claim shape the identity provider never issues.
 * </p>
 */
@DisplayName("Auth0RoleConverter")
class Auth0RoleConverterTest {

    private static final String ROLES_CLAIM = "https://api.test.com/roles";

    private final SecurityConfiguration.Auth0RoleConverter converter =
            new SecurityConfiguration.Auth0RoleConverter(ROLES_CLAIM);

    private static Jwt jwtWith(String claim, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("auth0|test")
                .claim(claim, value)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static List<String> authorities(Collection<GrantedAuthority> granted) {
        return granted.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("grants roles from the Auth0 claim with a ROLE_ prefix")
    void shouldGrantRolesFromTheAuth0Claim() {
        Collection<GrantedAuthority> granted = converter.convert(jwtWith(ROLES_CLAIM, List.of("ADMIN", "USER")));

        assertThat(authorities(granted)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("does not prefix a role that already carries ROLE_")
    void shouldNotDoublePrefix() {
        Collection<GrantedAuthority> granted = converter.convert(jwtWith(ROLES_CLAIM, List.of("ROLE_ADMIN")));

        assertThat(authorities(granted)).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("grants nothing when the Auth0 claim is absent")
    void shouldGrantNothingWithoutTheClaim() {
        Collection<GrantedAuthority> granted = converter.convert(jwtWith("unrelated", "value"));

        assertThat(granted).isEmpty();
    }

    @Test
    @DisplayName("ignores Keycloak's realm_access.roles")
    void shouldIgnoreRealmAccessRoles() {
        Jwt keycloakShaped = jwtWith("realm_access", Map.of("roles", List.of("ADMIN")));

        Collection<GrantedAuthority> granted = converter.convert(keycloakShaped);

        assertThat(granted)
                .as("a claim shape Auth0 never issues must not grant authorities")
                .isEmpty();
    }
}
