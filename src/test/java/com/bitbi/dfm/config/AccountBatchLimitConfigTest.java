package com.bitbi.dfm.config;

import com.bitbi.dfm.account.application.AccountProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the per-account concurrent batch limit configuration.
 * <p>
 * Only {@code account.max-concurrent-batches} is read by the application
 * ({@link com.bitbi.dfm.batch.application.BatchLifecycleService} via {@link AccountProperties}).
 * The {@code batch.max.active.per-account} key was declared in {@code application.yml} but never
 * read by any bean — a misleading knob that suggested a limit of 10 while the effective limit is 5.
 * These tests pin the surviving key and prevent the dead one from coming back.
 * </p>
 */
@DisplayName("Account batch limit configuration")
class AccountBatchLimitConfigTest {

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    private static PropertySource<?> applicationYaml() throws IOException {
        List<PropertySource<?>> sources = LOADER.load("application.yml", new ClassPathResource("application.yml"));
        assertEquals(1, sources.size(), "application.yml should yield a single document");
        return sources.get(0);
    }

    @Test
    @DisplayName("application.yml must not declare the unread batch.max.active.per-account key")
    void shouldNotDeclareUnreadBatchMaxActivePerAccountKey() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertFalse(yaml.containsProperty("batch.max.active.per-account"),
                "batch.max.active.per-account is read by no bean — it must not be declared");
    }

    @Test
    @DisplayName("application.yml keeps the effective account.max-concurrent-batches knob")
    void shouldKeepEffectiveConcurrentBatchLimitKnob() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        Object value = yaml.getProperty("account.max-concurrent-batches");
        assertNotNull(value, "account.max-concurrent-batches is the knob BatchLifecycleService actually reads");
        assertEquals("${ACCOUNT_MAX_CONCURRENT_BATCHES:5}", value.toString(),
                "effective concurrent batch limit must stay 5 (overridable via ACCOUNT_MAX_CONCURRENT_BATCHES)");
    }

    @Test
    @DisplayName("batch timeout configuration survives the cleanup")
    void shouldKeepBatchTimeoutConfiguration() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertTrue(yaml.containsProperty("batch.timeout.minutes"),
                "batch.timeout.minutes is read by the timeout scheduler and must stay");
    }

    private static AccountProperties bind(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("account", Bindable.of(AccountProperties.class))
                .orElseThrow(() -> new AssertionError("account.* properties must bind to AccountProperties"));
    }

    @Test
    @DisplayName("AccountProperties bound from application.yml yields a limit of 5")
    void shouldBindConcurrentBatchLimitOfFive() {
        // Isolated from the process environment: a runner exporting ACCOUNT_MAX_CONCURRENT_BATCHES
        // must not decide whether the documented default is 5.
        AccountProperties properties = bind(IsolatedEnvironments.loadConfig());

        assertEquals(5, properties.getMaxConcurrentBatches(),
                "effective per-account concurrent batch limit must remain 5");
    }

    @Test
    @DisplayName("ACCOUNT_MAX_CONCURRENT_BATCHES still overrides the default")
    void shouldHonourConcurrentBatchLimitOverride() {
        AccountProperties properties = bind(
                IsolatedEnvironments.loadConfig(Map.of("ACCOUNT_MAX_CONCURRENT_BATCHES", "9")));

        assertEquals(9, properties.getMaxConcurrentBatches(),
                "the documented override knob must keep working — the default is a default, not a constant");
    }
}
