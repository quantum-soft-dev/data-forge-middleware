package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PlaceholderResolutionException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * Reading the property is what forces resolution, so these tests fail loudly on a dangling reference
 * instead of deferring the failure to a profile-less boot.
 * </p>
 * <p>
 * The environment is cut off from the process environment ({@link IsolatedEnvironments}): a runner
 * that exports {@code AUTH0_AUDIENCE} must not change the outcome, in either direction. The override
 * knob is exercised explicitly instead, and {@link #shouldStillDetectAnUnresolvablePlaceholder()}
 * proves the isolation did not neuter the check this class exists to perform.
 * </p>
 */
@DisplayName("OAuth2 resource server audience configuration")
class OAuth2AudienceConfigTest {

    private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String DEFAULT_AUDIENCE = "https://api.dataforge.com";

    @Test
    @DisplayName("resolves without an active profile")
    void shouldResolveAudienceWithoutActiveProfile() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig();

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES),
                "a profile-less boot must not fail on an unresolvable audience placeholder");
        assertEquals(DEFAULT_AUDIENCE, audience);
    }

    @Test
    @DisplayName("resolves on the dev profile")
    void shouldResolveAudienceOnDevProfile() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig("dev");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals(DEFAULT_AUDIENCE, audience);
    }

    @Test
    @DisplayName("resolves on the test profile")
    void shouldResolveAudienceOnTestProfile() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig("test");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals("https://api.test.com", audience,
                "the test profile's own auth0.api.audience must drive the expected audience");
    }

    @Test
    @DisplayName("prod keeps resolving to the same audience")
    void shouldResolveAudienceOnProdProfile() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig("prod");

        String audience = assertDoesNotThrow(() -> environment.getProperty(AUDIENCES));
        assertEquals(DEFAULT_AUDIENCE, audience,
                "prod behaviour is unchanged: same AUTH0_AUDIENCE knob, same default");
    }

    @Test
    @DisplayName("AUTH0_AUDIENCE overrides the default on every profile")
    void shouldTrackAuth0AudienceOverride() {
        String override = "https://audience.from.env.example.com";

        for (String profile : new String[]{"", "dev", "prod"}) {
            StandardEnvironment environment = profile.isEmpty()
                    ? IsolatedEnvironments.loadConfig(Map.of("AUTH0_AUDIENCE", override))
                    : IsolatedEnvironments.loadConfig(Map.of("AUTH0_AUDIENCE", override), profile);

            assertEquals(override, environment.getProperty(AUDIENCES),
                    "profile '" + profile + "' must resolve the audience from the shared AUTH0_AUDIENCE knob");
        }
    }

    /**
     * Meta-guard: an unresolvable placeholder must still blow up inside the isolated environment.
     * <p>
     * Isolation could quietly defeat the point of this class — if a dangling reference resolved to
     * {@code null} instead of throwing, every {@code assertDoesNotThrow} above would pass no matter
     * how broken the configuration was. This pins that a dangling {@code ${...}} is still an error,
     * so the regression that motivated T03 would still be caught.
     * </p>
     */
    @Test
    @DisplayName("still detects an unresolvable placeholder")
    void shouldStillDetectAnUnresolvablePlaceholder() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig(
                Map.of(AUDIENCES, "${auth0.no-such-audience-property}"), "test");

        assertThrows(PlaceholderResolutionException.class, () -> environment.getProperty(AUDIENCES),
                "a dangling placeholder must fail loudly, otherwise this class guards nothing");
    }
}
