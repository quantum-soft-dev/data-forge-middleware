package com.bitbi.dfm.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the wiring between the deployed ConfigMap and the allowlist {@link MetricsScrapeAccess}
 * ultimately builds — the part {@link MetricsScrapeAccessTest} cannot see because it is handed a
 * ready-made list.
 * <p>
 * Three names have to agree for a scrape to work: {@code METRICS_SCRAPE_ALLOWED_CIDRS} in the dev
 * ConfigMap, the placeholder in {@code application.yml}, and the {@code @Value} expression on
 * {@link SecurityConfiguration}'s constructor. Nothing fails loudly when they drift — the list
 * simply comes out empty, every scrape gets a 403, and the suite stays green, which is the exact
 * failure mode issue #83 was opened over. {@code SecurityConfiguration} is {@code @Profile("!test")}
 * and the contract test exercises a single hand-written CIDR, so this test walks the real chain
 * instead: it reads the value out of the checked-in ConfigMap, resolves it through the real
 * {@code application.yml} placeholder, converts it the way Boot converts a {@code @Value} into a
 * {@code List<String>}, and asserts on the resulting authorization decisions.
 * </p>
 */
@DisplayName("Metrics Scrape Binding Tests")
class MetricsScrapeBindingTest {

    private static final String ENV_VAR = "METRICS_SCRAPE_ALLOWED_CIDRS";
    private static final Path DEV_CONFIGMAP = Path.of("k8s/overlays/dev/configmap-patch.yaml");

    /**
     * The {@code @Value} expression on the constructor parameter the allowlist is built from.
     */
    private static String valueExpression() {
        for (Constructor<?> constructor : SecurityConfiguration.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null) {
                    return value.value();
                }
            }
        }
        throw new AssertionError("SecurityConfiguration no longer takes a @Value parameter — "
                + "the metrics allowlist is no longer bound from a property");
    }

    /**
     * @return the property name inside {@code ${...:default}}
     */
    private static String propertyNameOf(String expression) {
        assertThat(expression).startsWith("${").endsWith("}");
        String body = expression.substring(2, expression.length() - 1);
        int colon = body.indexOf(':');
        return colon < 0 ? body : body.substring(0, colon);
    }

    private static StandardEnvironment environmentWith(Map<String, Object> envVars) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        loaded.forEach(source -> environment.getPropertySources().addLast(source));
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", envVars));
        return environment;
    }

    /**
     * Resolves the expression the same way the container would and converts it to the declared
     * {@code List<String>}, so a value that does not split into entries is caught here.
     */
    private static List<String> bind(StandardEnvironment environment) {
        String resolved = environment.resolveRequiredPlaceholders(valueExpression());
        @SuppressWarnings("unchecked")
        List<String> cidrs = (List<String>) ApplicationConversionService.getSharedInstance().convert(
                resolved,
                TypeDescriptor.valueOf(String.class),
                TypeDescriptor.collection(List.class, TypeDescriptor.valueOf(String.class)));
        return cidrs == null ? List.of() : cidrs;
    }

    private static boolean granted(List<String> cidrs, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr(remoteAddr);
        return MetricsScrapeAccess.authorizationManager(cidrs)
                .check(() -> (Authentication) null, new RequestAuthorizationContext(request))
                .isGranted();
    }

    @SuppressWarnings("unchecked")
    private static String devConfigMapValue() throws IOException {
        assertThat(DEV_CONFIGMAP).as("dev overlay ConfigMap, read relative to the project directory")
                .exists();
        Map<String, Object> configMap = new Yaml().load(Files.readString(DEV_CONFIGMAP));
        Map<String, Object> data = (Map<String, Object>) configMap.get("data");
        Object value = data.get(ENV_VAR);
        assertThat(value).as("%s in the dev overlay ConfigMap", ENV_VAR).isNotNull();
        return value.toString();
    }

    @Test
    @DisplayName("Should read the allowlist from the property application.yml populates")
    void shouldBindFromThePropertyApplicationYmlPopulates() throws IOException {
        String propertyName = propertyNameOf(valueExpression());

        StandardEnvironment environment = environmentWith(Map.of());

        assertThat(environment.getPropertySources().stream()
                .filter(source -> source.containsProperty(propertyName))
                .map(source -> String.valueOf(source.getProperty(propertyName)))
                .findFirst())
                .as("application.yml must define %s and fill it from $%s", propertyName, ENV_VAR)
                .hasValue("${" + ENV_VAR + ":}");
    }

    @Test
    @DisplayName("Should grant the collector and loopback when the dev ConfigMap value is bound")
    void shouldGrantTheDevScrapeSources() throws IOException {
        List<String> cidrs = bind(environmentWith(Map.of(ENV_VAR, devConfigMapValue())));

        assertThat(granted(cidrs, "10.4.1.7")).as("managed-Prometheus collector on a pod IP").isTrue();
        assertThat(granted(cidrs, "127.0.0.1")).as("kubectl port-forward over IPv4").isTrue();
        assertThat(granted(cidrs, "0:0:0:0:0:0:0:1")).as("kubectl port-forward over IPv6").isTrue();
        assertThat(granted(cidrs, "35.191.4.9")).as("a Google front-end address").isFalse();
        assertThat(granted(cidrs, "10.0.0.8")).as("an address outside the pod range").isFalse();
    }

    @Test
    @DisplayName("Should deny everyone in an environment that does not set the variable")
    void shouldDenyWhenTheVariableIsUnset() throws IOException {
        List<String> cidrs = bind(environmentWith(Map.of()));

        assertThat(granted(cidrs, "10.4.1.7")).isFalse();
        assertThat(granted(cidrs, "127.0.0.1")).isFalse();
        assertThat(granted(cidrs, "0:0:0:0:0:0:0:1")).isFalse();
    }
}
