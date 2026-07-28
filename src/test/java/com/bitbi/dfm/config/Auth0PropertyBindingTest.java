package com.bitbi.dfm.config;

import com.bitbi.dfm.auth.config.Auth0Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that every {@code auth0.*} key the YAML declares is actually read by something.
 * <p>
 * {@link Auth0Properties} is a {@code @ConfigurationProperties("auth0")} record with nested
 * {@code Management} and {@code Api} groups, so it binds {@code auth0.management.client-id} and
 * ignores the flat {@code auth0.management-client-id} entirely. A flat key is not an error at
 * startup — it simply binds to nothing — which makes it worse than a typo: the file states one
 * configuration and the application runs on another, and nothing says so.
 * </p>
 * <p>
 * Spring's relaxed binding means the comparison has to ignore case and separators, so
 * {@code claims-namespace} and {@code claimsNamespace} are the same key here, exactly as they are
 * to the binder.
 * </p>
 */
@DisplayName("auth0.* configuration keys")
class Auth0PropertyBindingTest {

    /** Canonical form: relaxed binding ignores case and separators. */
    private static String canonical(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** Every property path {@link Auth0Properties} can bind, in canonical form. */
    private static Set<String> bindablePaths() {
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(Auth0Properties.class, "", paths);
        return paths;
    }

    private static void collectPaths(Class<?> type, String prefix, Set<String> paths) {
        for (RecordComponent component : type.getRecordComponents()) {
            String path = prefix + canonical(component.getName());
            if (component.getType().isRecord()) {
                collectPaths(component.getType(), path + ".", paths);
            } else {
                paths.add(path);
            }
        }
    }

    /** Keys declared under {@code auth0.} by the YAML for the given profile. */
    private static Set<String> declaredAuth0Keys(String... profiles) {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig(profiles);
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("auth0.")) {
                        keys.add(name);
                    }
                }
            }
        }
        return keys;
    }

    @ParameterizedTest(name = "profile ''{0}''")
    @ValueSource(strings = {"", "dev", "test", "prod"})
    @DisplayName("every declared key binds to an Auth0Properties component")
    void shouldNotDeclareUnboundAuth0Keys(String profile) {
        Set<String> bindable = bindablePaths();
        Set<String> orphans = new TreeSet<>();

        for (String key : declaredAuth0Keys(profile.isEmpty() ? new String[0] : new String[]{profile})) {
            String path = canonical(key.substring("auth0.".length()));
            if (!bindable.contains(path)) {
                orphans.add(key);
            }
        }

        assertThat(orphans)
                .as("auth0.* keys that bind to nothing — the file claims a configuration the "
                        + "application never reads")
                .isEmpty();
    }

    @Test
    @DisplayName("the Management credentials resolve from the AUTH0_MGMT_* knobs")
    void shouldResolveManagementCredentialsFromEnvironment() {
        StandardEnvironment environment = IsolatedEnvironments.loadConfig(
                java.util.Map.of(
                        "AUTH0_MGMT_CLIENT_ID", "client-id-from-env",
                        "AUTH0_MGMT_CLIENT_SECRET", "client-secret-from-env"),
                "prod");

        assertThat(environment.getProperty("auth0.management.client-id"))
                .as("prod must take the Management client id from AUTH0_MGMT_CLIENT_ID")
                .isEqualTo("client-id-from-env");
        assertThat(environment.getProperty("auth0.management.client-secret"))
                .isEqualTo("client-secret-from-env");
    }

    /**
     * Meta-guard: the orphan check must actually be able to fail. If {@link #bindablePaths()}
     * silently returned everything, {@link #shouldNotDeclareUnboundAuth0Keys} would pass against
     * any configuration at all.
     */
    @Test
    @DisplayName("the orphan check rejects a flat key")
    void shouldRejectAFlatKey() {
        assertThat(bindablePaths())
                .as("the nested form binds")
                .contains("management.clientid")
                .as("the flat form does not — this is the whole bug being guarded against")
                .doesNotContain("managementclientid");
    }
}
