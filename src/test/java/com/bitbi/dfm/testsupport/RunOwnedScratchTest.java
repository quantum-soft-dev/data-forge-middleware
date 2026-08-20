package com.bitbi.dfm.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RunOwnedScratch} expands {@code ${key:default}} the way the Spring {@code Environment}
 * would (issue #187), and must not do it through a constructor marked {@code forRemoval}
 * (issue #220, folded into #228).
 *
 * <p>The four semantics to keep: prefix {@code ${}, suffix {@code }}, value separator {@code :},
 * and <b>not</b> ignoring an unresolvable placeholder. The 4-arg
 * {@code PropertyPlaceholderHelper(String, String, String, boolean)} is {@code @Deprecated(since
 * = "6.2", forRemoval = true)}; the 5-arg form adds a nullable escape character and is the
 * replacement. Passing {@code null} for the escape is what the 4-arg constructor already did,
 * so the expansion does not change.</p>
 */
@DisplayName("RunOwnedScratch placeholder expansion (#228 / #220)")
class RunOwnedScratchTest {

    private static final String FOUR_ARG_CALL =
            "new PropertyPlaceholderHelper(\"${\", \"}\", \":\", false)";

    private static final String FIVE_ARG_CALL =
            "new PropertyPlaceholderHelper(\"${\", \"}\", \":\", null, false)";

    @Test
    @DisplayName("an unset key with a default expands to the default")
    void shouldExpandADefaultWhenTheKeyIsUnset() {
        String previous = System.getProperty("dfm.test.run-owned-scratch.missing");
        System.clearProperty("dfm.test.run-owned-scratch.missing");
        try {
            Path resolved = RunOwnedScratch.resolveDeclared(
                    "${dfm.test.run-owned-scratch.missing:scratch-default}");
            assertTrue(resolved.endsWith("scratch-default"),
                    "default after ':' was not applied: " + resolved);
        } finally {
            restoreProperty("dfm.test.run-owned-scratch.missing", previous);
        }
    }

    @Test
    @DisplayName("a set system property wins over the default")
    void shouldPreferASystemPropertyOverTheDefault() {
        String previous = System.getProperty("dfm.test.run-owned-scratch.present");
        System.setProperty("dfm.test.run-owned-scratch.present", "from-property");
        try {
            Path resolved = RunOwnedScratch.resolveDeclared(
                    "${dfm.test.run-owned-scratch.present:scratch-default}");
            assertTrue(resolved.endsWith("from-property"),
                    "system property did not win over the default: " + resolved);
        } finally {
            restoreProperty("dfm.test.run-owned-scratch.present", previous);
        }
    }

    @Test
    @DisplayName("an unresolvable placeholder throws rather than being copied through")
    void shouldNotIgnoreAnUnresolvablePlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> RunOwnedScratch.resolveDeclared("${dfm.test.run-owned-scratch.no-such-key}"),
                "ignoreUnresolvablePlaceholders must stay false: an unset key without a default "
                        + "has to fail here rather than in a context");
    }

    @Test
    @DisplayName("the placeholder helper is the form not marked for removal")
    void shouldNotUseTheRemovalMarkedConstructor() throws IOException {
        String source = Files.readString(sourceFile());
        assertFalse(source.contains(FOUR_ARG_CALL),
                "RunOwnedScratch still calls PropertyPlaceholderHelper(prefix, suffix, separator, "
                        + "boolean), which is @Deprecated(forRemoval = true) since Spring 6.2 and "
                        + "becomes a compileTestJava error on the Boot bump that drops it (#220)");
        assertTrue(source.contains(FIVE_ARG_CALL),
                "expected the 5-arg constructor with a null escape character (same four "
                        + "semantics: prefix, suffix, value separator, do not ignore unresolvable)");
    }

    private static Path sourceFile() {
        return RunOwnedScratch.projectRoot()
                .resolve("src/test/java/com/bitbi/dfm/testsupport/RunOwnedScratch.java");
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
