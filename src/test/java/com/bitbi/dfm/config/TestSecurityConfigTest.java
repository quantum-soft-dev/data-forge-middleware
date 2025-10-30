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

        // Verify claims (subject is now accountId UUID from test-data.sql)
        assert jwt.getSubject().equals("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        assert jwt.getClaim("email").equals("admin@test.com");
        assert jwt.getClaim("accountId").equals("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

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
