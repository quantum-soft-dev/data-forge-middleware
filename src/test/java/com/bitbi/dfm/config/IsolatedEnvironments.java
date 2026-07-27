package com.bitbi.dfm.config;

import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Collections;
import java.util.Map;

/**
 * Builds an {@link StandardEnvironment} loaded from the real {@code application*.yml} files but cut
 * off from the process environment.
 * <p>
 * Config assertions must be decided by what the YAML says, not by what happens to be exported on the
 * machine running the build. A CI runner with {@code AUTH0_AUDIENCE} or
 * {@code ACCOUNT_MAX_CONCURRENT_BATCHES} set would otherwise fail these tests while the
 * configuration is perfectly valid — a false positive that also means the test was never asserting
 * what it claimed.
 * </p>
 * <p>
 * The system property sources are replaced by empty maps under their original names rather than
 * removed, so anything that looks them up by name still finds a source — it just finds nothing in
 * it. Placeholder resolution is otherwise untouched: a dangling {@code ${...}} still throws, which
 * is what the audience guard relies on (and what
 * {@code OAuth2AudienceConfigTest#shouldStillDetectAnUnresolvablePlaceholder} pins).
 * </p>
 */
final class IsolatedEnvironments {

    private IsolatedEnvironments() {
    }

    /**
     * @param overrides properties that take precedence over the loaded YAML, standing in for
     *                  environment variables without touching the real process environment
     * @param profiles  profiles to activate while loading config data
     */
    static StandardEnvironment loadConfig(Map<String, Object> overrides, String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();

        sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        Collections.emptyMap()));
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Collections.emptyMap()));

        if (!overrides.isEmpty()) {
            sources.addFirst(new MapPropertySource("test-overrides", overrides));
        }

        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);
        return environment;
    }

    /** Config loaded from the YAML alone, with nothing overriding it. */
    static StandardEnvironment loadConfig(String... profiles) {
        return loadConfig(Collections.emptyMap(), profiles);
    }
}
