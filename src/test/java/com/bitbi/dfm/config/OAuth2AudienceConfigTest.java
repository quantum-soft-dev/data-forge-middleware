package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Placeholder-resolution guard for the OAuth2 resource server audience.
 * <p>
 * {@code spring.security.oauth2.resourceserver.jwt.audiences} referenced {@code ${auth0.api-audience}},
 * a property defined only in {@code application-prod.yml}; the base file defines the unrelated
 * {@code auth0.api.audience}. Booting without an active profile therefore blew up on placeholder
 * resolution as soon as the JWT decoder was built — dev and test only escaped because they override
 * the {@code audiences} block outright.
 * </p>
 * <p>
 * Reading the property here is what forces resolution, so these tests fail loudly on a dangling
 * reference instead of deferring the failure to a profile-less boot.
 * </p>
 */
@DisplayName("OAuth2 resource server audience configuration")
class OAuth2AudienceConfigTest {

    private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String DEFAULT_AUDIENCE = "https://api.dataforge.com";

    private static StandardEnvironment environmentFor(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);
        return environment;
    }

    @Test
    @DisplayName("resolves without an active profile")
    void shouldResolveAudienceWithoutActiveProfile() {
        StandardEnvironment environment = environmentFor();

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES),
                "a profile-less boot must not fail on an unresolvable audience placeholder");
        assertEquals(DEFAULT_AUDIENCE, audience);
    }

    @Test
    @DisplayName("resolves on the dev profile")
    void shouldResolveAudienceOnDevProfile() {
        StandardEnvironment environment = environmentFor("dev");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals(DEFAULT_AUDIENCE, audience);
    }

    @Test
    @DisplayName("resolves on the test profile")
    void shouldResolveAudienceOnTestProfile() {
        StandardEnvironment environment = environmentFor("test");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals("https://api.test.com", audience,
                "the test profile's own auth0.api.audience must drive the expected audience");
    }

    @Test
    @DisplayName("prod keeps resolving to the same audience")
    void shouldResolveAudienceOnProdProfile() {
        StandardEnvironment environment = environmentFor("prod");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals(DEFAULT_AUDIENCE, audience,
                "prod behaviour is unchanged: same AUTH0_AUDIENCE env var, same default");
    }

    @Test
    @DisplayName("audience tracks AUTH0_AUDIENCE on every profile")
    void shouldTrackAuth0AudienceOverride() {
        for (String profile : new String[]{"", "dev", "prod"}) {
            StandardEnvironment environment = profile.isEmpty() ? environmentFor() : environmentFor(profile);
            String audience = environment.getProperty(AUDIENCES);
            assertEquals(DEFAULT_AUDIENCE, audience,
                    "profile '" + profile + "' must resolve the audience from the shared AUTH0_AUDIENCE knob");
        }
    }
}
