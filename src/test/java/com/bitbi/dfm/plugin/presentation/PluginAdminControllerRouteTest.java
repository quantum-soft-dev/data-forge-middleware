package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the plugin controllers to the shared {@link ApiRoutes} constants.
 * <p>
 * Every plugin controller builds its mapping from {@code ApiRoutes} — except
 * {@link PluginAdminController}, which hardcoded the literal {@code "/api/v1/admin/plugins"}. That
 * let the mapping drift away from {@link ApiRoutes#PLUGINS_ADMIN} (already used by the security
 * chain and by the {@code /audit} sub-route) with nothing failing.
 * </p>
 * <p>
 * The literal-vs-constant distinction is invisible to reflection — both compile to the same string —
 * so the convention is enforced against the source text.
 * </p>
 */
@DisplayName("Plugin controller route wiring")
class PluginAdminControllerRouteTest {

    private static final Path PLUGIN_PRESENTATION = Path.of(
            "src/main/java/com/bitbi/dfm/plugin/presentation");

    /** Class-level {@code @RequestMapping} (or {@code path =}) holding a literal API path. */
    private static final Pattern HARDCODED_PATH = Pattern.compile(
            "@RequestMapping\\(\\s*(?:path\\s*=\\s*)?\"(/api/[^\"]*)\"");

    @Test
    @DisplayName("no plugin controller hardcodes an API path literal")
    void shouldNotHardcodeApiPathLiterals() throws IOException {
        assertTrue(Files.isDirectory(PLUGIN_PRESENTATION),
                "plugin presentation sources not found at " + PLUGIN_PRESENTATION.toAbsolutePath());

        try (Stream<Path> sources = Files.list(PLUGIN_PRESENTATION)) {
            List<Path> controllers = sources
                    .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .toList();

            assertTrue(controllers.size() >= 2, "expected several plugin controllers to scan");

            for (Path controller : controllers) {
                Matcher matcher = HARDCODED_PATH.matcher(Files.readString(controller));
                if (matcher.find()) {
                    throw new AssertionError(controller.getFileName()
                            + " hardcodes \"" + matcher.group(1) + "\" — use an ApiRoutes constant");
                }
            }
        }
    }

    @Test
    @DisplayName("PluginAdminController resolves to ApiRoutes.PLUGINS_ADMIN")
    void shouldMapApiRoutesConstant() {
        RequestMapping mapping = PluginAdminController.class.getAnnotation(RequestMapping.class);

        assertNotNull(mapping, "PluginAdminController must be mapped");
        assertArrayEquals(new String[]{ApiRoutes.PLUGINS_ADMIN}, mapping.value());
    }

    @Test
    @DisplayName("keeps the published /api/v1/admin/plugins path")
    void shouldKeepPublishedPath() {
        // The switch to the constant must be a pure refactor: the wire path cannot move.
        assertEquals("/api/v1/admin/plugins", ApiRoutes.PLUGINS_ADMIN);
        assertEquals("/api/v1/admin/plugins/audit", ApiRoutes.PLUGINS_ADMIN_AUDIT);
    }
}
