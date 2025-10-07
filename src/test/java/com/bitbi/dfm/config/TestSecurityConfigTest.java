package com.bitbi.dfm.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit test for TestSecurityConfig JWT conversion.
 */
class TestSecurityConfigTest {

    @Test
    void testAdminTokenDecoding() {
        TestSecurityConfig config = new TestSecurityConfig();

        // Decode admin token
        Jwt jwt = config.jwtDecoder().decode("mock.admin.jwt.token");

        // Verify claims
        assert jwt.getSubject().equals("admin-user");
        assert jwt.getClaim("email").equals("admin@test.com");

        // Verify authorities conversion
        var authorities = config.jwtAuthenticationConverter()
                .convert(jwt)
                .getAuthorities();

        assert authorities.size() == 1;
        assert authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    @Test
    void testUserTokenDecoding() {
        TestSecurityConfig config = new TestSecurityConfig();

        // Decode user token
        Jwt jwt = config.jwtDecoder().decode("mock.user.jwt.token");

        // Verify authorities conversion
        var authorities = config.jwtAuthenticationConverter()
                .convert(jwt)
                .getAuthorities();

        assert authorities.size() == 1;
        assert authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
    }
}
