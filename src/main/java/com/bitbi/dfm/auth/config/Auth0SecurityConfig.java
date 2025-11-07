package com.bitbi.dfm.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Security configuration for Auth0 JWT validation.
 * <p>
 * This configuration sets up Spring Security OAuth2 Resource Server to validate
 * JWT tokens issued by Auth0. It includes custom audience validation and role
 * extraction from custom claims.
 * </p>
 *
 * <h3>JWT Validation:</h3>
 * <ul>
 *   <li>Issuer validation: Auth0 tenant URL (https://{domain}/)</li>
 *   <li>Audience validation: API identifier (e.g., https://api.dataforge.com)</li>
 *   <li>Signature validation: RS256 (fetches JWKS from Auth0)</li>
 *   <li>Expiration validation: Token must not be expired</li>
 * </ul>
 *
 * <h3>Custom Claims:</h3>
 * <ul>
 *   <li>Roles: Extracted from https://api.dataforge.com/roles claim (Auth0 Action)</li>
 *   <li>Account ID: Extracted from https://api.dataforge.com/accountId claim</li>
 * </ul>
 *
 * <h3>Integration with SecurityConfiguration:</h3>
 * <p>
 * This configuration provides the {@link JwtDecoder} bean that can be used by
 * SecurityConfiguration filter chains to validate Auth0 tokens for admin endpoints.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@Profile("!test")
public class Auth0SecurityConfig {

    private final Auth0Properties auth0Properties;

    public Auth0SecurityConfig(Auth0Properties auth0Properties) {
        this.auth0Properties = auth0Properties;
    }

    /**
     * Create JwtDecoder for Auth0 JWT validation.
     * <p>
     * Configures:
     * <ul>
     *   <li>JWKS endpoint: https://{domain}/.well-known/jwks.json</li>
     *   <li>Issuer validation: Auth0 tenant issuer</li>
     *   <li>Audience validation: API identifier</li>
     * </ul>
     * </p>
     *
     * @return configured JwtDecoder
     */
    @Bean
    public JwtDecoder auth0JwtDecoder() {
        NimbusJwtDecoder jwtDecoder = JwtDecoders.fromIssuerLocation(auth0Properties.api().issuer());

        // Add custom validators (audience + default validators)
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(auth0Properties.api().audience());
        OAuth2TokenValidator<Jwt> defaultValidators = JwtValidators.createDefaultWithIssuer(auth0Properties.api().issuer());
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(defaultValidators, audienceValidator);

        jwtDecoder.setJwtValidator(validator);

        return jwtDecoder;
    }

    /**
     * Create JwtAuthenticationConverter for Auth0 custom claims.
     * <p>
     * Extracts roles from the custom "https://api.dataforge.com/roles" claim
     * and converts them to Spring Security authorities (ROLE_ADMIN, ROLE_USER, etc.).
     * </p>
     *
     * @return configured JwtAuthenticationConverter
     */
    @Bean
    public JwtAuthenticationConverter auth0JwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new Auth0RolesConverter());
        return converter;
    }

    /**
     * Custom audience validator for Auth0 JWT tokens.
     * <p>
     * Validates that the JWT's "aud" claim contains the expected API audience.
     * This prevents tokens issued for other APIs from being accepted.
     * </p>
     */
    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String audience;

        AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (jwt.getAudience() != null && jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }

            return OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token",
                            "Required audience '" + audience + "' not found in token",
                            null
                    )
            );
        }
    }

    /**
     * Custom roles converter for Auth0 JWT tokens.
     * <p>
     * Extracts roles from the custom "https://api.dataforge.com/roles" claim
     * and converts them to Spring Security GrantedAuthority objects with "ROLE_" prefix.
     * </p>
     *
     * <h4>Example:</h4>
     * <pre>
     * JWT claim: "https://api.dataforge.com/roles": ["admin", "user"]
     * Authorities: [ROLE_ADMIN, ROLE_USER]
     * </pre>
     */
    static class Auth0RolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        private static final String ROLES_CLAIM = "https://api.dataforge.com/roles";

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Collection<GrantedAuthority> authorities = new ArrayList<>();

            // Extract custom roles claim
            Object rolesClaim = jwt.getClaim(ROLES_CLAIM);

            if (rolesClaim instanceof List<?> roles) {
                authorities.addAll(
                        roles.stream()
                                .filter(role -> role instanceof String)
                                .map(role -> (String) role)
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                                .collect(Collectors.toList())
                );
            }

            // Fallback: extract standard "scope" claim if present
            String scopeClaim = jwt.getClaimAsString("scope");
            if (scopeClaim != null && !scopeClaim.isEmpty()) {
                authorities.addAll(
                        List.of(scopeClaim.split(" ")).stream()
                                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                                .collect(Collectors.toList())
                );
            }

            return authorities;
        }
    }
}
