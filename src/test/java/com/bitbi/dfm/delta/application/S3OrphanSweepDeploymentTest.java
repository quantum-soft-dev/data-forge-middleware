package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #351: where the S3 orphan sweep (#158) is allowed to delete.
 *
 * <p>{@code delta.s3-orphan.dry-run} ships {@code true}, so the sweep only reports until an
 * operator has read a pass and turned deleting on for that deployment. Dev has had its pass read
 * (the analysis is on #351) and deletes. Stage and prod are a separate decision, made once they
 * have a report of their own. {@code reclaim-unknown-sites} stays {@code false} everywhere, because
 * nothing establishes that a bucket belongs to one database alone.</p>
 *
 * <p>The manifests carry these decisions, and nothing the compiler or CI runs reads them. A
 * ConfigMap key the application does not bind configures nothing, and a manifest that flips a flag
 * without a decision behind it is exactly what the dry-run default exists to prevent. So both are
 * held here, over every file under {@code k8s/}.</p>
 */
@DisplayName("The S3 orphan sweep deletes only where a dry run was read (#351)")
class S3OrphanSweepDeploymentTest {

    private static final String DRY_RUN_KEY = "DELTA_S3_ORPHAN_DRY_RUN";
    private static final String RECLAIM_UNKNOWN_KEY = "DELTA_S3_ORPHAN_RECLAIM_UNKNOWN_SITES";

    /**
     * The relaxed-binding spellings of the same two properties. Spring binds an environment
     * variable named after the property itself as well as the placeholder, so a manifest could set
     * the flag through either spelling.
     */
    private static final String DRY_RUN_RELAXED = "DELTA_S3ORPHAN_DRYRUN";
    private static final String RECLAIM_UNKNOWN_RELAXED = "DELTA_S3ORPHAN_RECLAIMUNKNOWNSITES";

    private static final Path DEV_CONFIGMAP_PATCH = Path.of("k8s/overlays/dev/configmap-patch.yaml");

    /** What a value reads as when the reader cannot resolve it to a literal: the guard fails closed. */
    static final String UNRESOLVED = "<not a literal>";

    @Test
    @DisplayName("the dev overlay turns the dry run off")
    void theDevOverlayDeletes() throws IOException {
        assertThat(declaredValues(read(DEV_CONFIGMAP_PATCH), DRY_RUN_KEY))
                .as("%s must set %s to \"false\": dev's dry run was read on #351", DEV_CONFIGMAP_PATCH, DRY_RUN_KEY)
                .containsExactly("false");
    }

    @Test
    @DisplayName("the ConfigMap keys are the placeholders application.yml actually binds")
    void theManifestKeysAreTheOnesTheApplicationReads() throws IOException {
        String applicationYaml = read(Path.of("src/main/resources/application.yml"));
        assertThat(applicationYaml)
                .as("a renamed placeholder leaves the manifests setting a variable nothing reads")
                .contains("dry-run: ${" + DRY_RUN_KEY + ":true}")
                .contains("reclaim-unknown-sites: ${" + RECLAIM_UNKNOWN_KEY + ":false}");
    }

    @Test
    @DisplayName("no other manifest turns the dry run off: stage and prod are a separate decision")
    void noOtherManifestTurnsTheDryRunOff() throws IOException {
        Map<Path, List<String>> offenders = new LinkedHashMap<>();
        for (Path file : manifests()) {
            if (file.equals(DEV_CONFIGMAP_PATCH)) {
                continue;
            }
            String body = read(file);
            List<String> values = new ArrayList<>(declaredValues(body, DRY_RUN_KEY));
            values.addAll(declaredValues(body, DRY_RUN_RELAXED));
            values.removeIf("true"::equals);
            if (!values.isEmpty()) {
                offenders.put(file, values);
            }
        }
        assertThat(offenders)
                .as("turning deleting on anywhere but dev needs that deployment's own dry-run report (#351, item 4)")
                .isEmpty();
    }

    @Test
    @DisplayName("no manifest reclaims the prefixes of sites the database has never heard of")
    void noManifestReclaimsUnknownSites() throws IOException {
        Map<Path, List<String>> offenders = new LinkedHashMap<>();
        for (Path file : manifests()) {
            String body = read(file);
            List<String> values = new ArrayList<>(declaredValues(body, RECLAIM_UNKNOWN_KEY));
            values.addAll(declaredValues(body, RECLAIM_UNKNOWN_RELAXED));
            values.removeIf("false"::equals);
            if (!values.isEmpty()) {
                offenders.put(file, values);
            }
        }
        assertThat(offenders)
                .as("reclaim-unknown-sites asserts that the bucket is exclusive to one database, which "
                        + "nothing checks. Remove a site-less prefix by hand once it is known to be this stand's (#351)")
                .isEmpty();
    }

    @Test
    @DisplayName("the reader sees every spelling of a key and fails closed on what it cannot resolve")
    void theReaderSeesEverySpelling() {
        String yaml = """
                data:
                  DELTA_S3_ORPHAN_DRY_RUN: "false"
                  # DELTA_S3_ORPHAN_DRY_RUN: "true" is only documented here
                env:
                  - name: DELTA_S3_ORPHAN_DRY_RUN
                    value: 'no'
                  - { name: DELTA_S3_ORPHAN_DRY_RUN, value: off }
                  - name: DELTA_S3_ORPHAN_DRY_RUN
                    valueFrom:
                      configMapKeyRef: { name: x, key: y }
                configMapGenerator:
                  - literals:
                      - DELTA_S3_ORPHAN_DRY_RUN=0
                  - DELTA_S3_ORPHAN_DRY_RUN_SOMETHING_ELSE: "true"
                """;
        assertThat(declaredValues(yaml, DRY_RUN_KEY))
                .containsExactlyInAnyOrder("false", "no", "off", UNRESOLVED, "0");
    }

    /**
     * Every value {@code key} is given in {@code yaml}, comments ignored: a map entry
     * ({@code KEY: value}), a {@code KEY=value} literal, a two-line env entry ({@code - name: KEY}
     * then {@code value: …}) and a flow-mapping env entry. An env entry whose value is not a
     * literal reads as {@link #UNRESOLVED}, since this guard cannot prove what it resolves to.
     */
    static List<String> declaredValues(String yaml, String key) {
        String body = withoutComments(yaml);
        String k = Pattern.quote(key);
        String value = "[\"']?([^\"'\\s,}]+)[\"']?";
        List<String> values = new ArrayList<>();
        collect(Pattern.compile("(?m)^\\s*(?:-\\s+)?" + k + "\\s*[:=]\\s*" + value), body, values);
        collect(Pattern.compile("\\{\\s*name\\s*:\\s*[\"']?" + k + "[\"']?\\s*,\\s*value\\s*:\\s*" + value), body, values);
        Matcher envName = Pattern.compile("(?m)^\\s*-\\s+name\\s*:\\s*[\"']?" + k + "[\"']?\\s*$").matcher(body);
        Pattern envValue = Pattern.compile("^\\s*value\\s*:\\s*" + value);
        while (envName.find()) {
            String rest = body.substring(envName.end()).stripLeading();
            String next = rest.lines().findFirst().orElse("");
            Matcher literal = envValue.matcher(next);
            values.add(literal.find() ? literal.group(1) : UNRESOLVED);
        }
        return values;
    }

    private static void collect(Pattern pattern, String body, List<String> into) {
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /** Drop whole-line and trailing comments, so a documented key is not read as a declared one. */
    private static String withoutComments(String yaml) {
        return yaml.lines()
                .map(line -> line.replaceFirst("(^|\\s)#.*$", ""))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static List<Path> manifests() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("k8s"))) {
            List<Path> yaml = files.filter(S3OrphanSweepDeploymentTest::isYaml).sorted().toList();
            assertThat(yaml).as("the scan found no manifests; a blind scan is not a clean one")
                    .contains(DEV_CONFIGMAP_PATCH, Path.of("k8s/base/configmap.yaml"));
            return yaml;
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
