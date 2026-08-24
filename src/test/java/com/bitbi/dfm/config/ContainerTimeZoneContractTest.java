package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * The deployed JVM runs in UTC by declaration, not by accident (issue #280).
 *
 * <p>Every zone-independent {@code TIMESTAMP} column in this schema holds a UTC wall clock. Until
 * this guard the deployed JVM ran in UTC because the base image happens to default to it, which is
 * a property of a third party's image and of nothing this repository says.</p>
 *
 * <p><strong>What still depends on the zone, and what no longer does.</strong> #280 declared it
 * because the Hibernate and raw-JDBC reads of such a column agreed only in UTC —
 * {@code hibernate.jdbc.time_zone: UTC} made the Hibernate path convert through the JVM's zone.
 * #282 removed that setting, so those two paths now agree in <em>any</em> zone and that reason is
 * gone. Two remain, and they are why this guard stays. The database's own clock is a producer for
 * these columns — a JPQL {@code SET … = CURRENT_TIMESTAMP} is evaluated by PostgreSQL in the
 * session's zone, which pgjdbc takes from the JVM's — so off UTC those statements would write a
 * local wall clock into a column everything else fills with UTC. And logs, timestamps in support
 * requests and anything else read by a human come out in the zone the container declares.</p>
 *
 * <p><strong>{@code ENV TZ}, not {@code -Duser.timezone} in {@code JAVA_OPTS}.</strong> That
 * variable is set wholesale by callers — {@code docker-compose.prod.yml} replaces it — so a flag
 * added there is dropped by the next deployment that tunes the heap, silently and in the direction
 * of the defect. {@code TZ} is read by the JVM through the OS default zone and is not on anyone's
 * path to overwrite.</p>
 *
 * <p>The second assertion is the other half: a manifest may not set {@code TZ} to something else.
 * An overlay pinning a local zone would satisfy the Dockerfile check and defeat it in the same
 * breath.</p>
 */
@DisplayName("The container declares the UTC time zone the read convention rests on")
class ContainerTimeZoneContractTest {

    /** Runtime stages — the builder compiles and runs nothing that reads a timestamp. */
    private static final List<String> RUNTIME_STAGES = List.of("production", "development");

    private static final Pattern FROM = Pattern.compile("(?m)^\\s*FROM\\s+\\S+(?:\\s+AS\\s+(\\S+))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENV_TZ = Pattern.compile("(?m)^\\s*ENV\\s+TZ\\s*=\\s*\"?([A-Za-z0-9/_+-]+)\"?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** A zone value: {@code Europe/Berlin}, {@code UTC}, {@code Etc/GMT+3}. */
    private static final String ZONE = "([A-Za-z0-9/_+-]+)";
    /** ConfigMap / compose {@code TZ: value}, and the compose list form {@code - TZ=value}. */
    private static final Pattern YAML_TZ_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*-?\\s*[\"']?TZ[\"']?\\s*[:=]\\s*[\"']?" + ZONE + "[\"']?\\s*$");
    /**
     * The Kubernetes container env form, where the name and the value are two separate lines:
     * {@code - name: TZ} then {@code value: "Europe/Berlin"} (or {@code valueFrom:}).
     */
    private static final Pattern YAML_TZ_ENV_NAME = Pattern.compile(
            "(?m)^\\s*-?\\s*name:\\s*[\"']?TZ[\"']?\\s*$");
    private static final Pattern YAML_ENV_VALUE = Pattern.compile(
            "^\\s*value:\\s*[\"']?" + ZONE + "[\"']?\\s*$");
    /** The flow-mapping spelling of the same entry: <code>{name: TZ, value: X}</code>. */
    private static final Pattern YAML_TZ_ENV_INLINE = Pattern.compile(
            "\\{\\s*name:\\s*[\"']?TZ[\"']?\\s*,\\s*value:\\s*[\"']?" + ZONE + "[\"']?\\s*}");
    /** What an unresolvable value reads as: the guard cannot prove it is UTC, so it fails closed. */
    private static final String UNRESOLVED = "<not a literal>";

    @Test
    @DisplayName("every runtime stage of the Dockerfile sets TZ=UTC")
    void everyRuntimeStageDeclaresUtc() {
        Map<String, String> declared = timeZonePerStage(read(RunOwnedScratch.projectRoot().resolve("Dockerfile")));
        for (String stage : RUNTIME_STAGES) {
            assertThat(declared)
                    .withFailMessage("Dockerfile stage '%s' must declare ENV TZ=UTC: pgjdbc takes the database "
                            + "session's zone from the JVM's, so a JPQL 'SET ... = CURRENT_TIMESTAMP' would write "
                            + "a local wall clock into a column everything else fills with UTC — and logs would "
                            + "print in that zone too (#280, #282, see README.md \"Time zones\"). "
                            + "Declared zones per stage: %s", stage, declared)
                    .containsEntry(stage, "UTC");
        }
    }

    @Test
    @DisplayName("no manifest overrides the zone with something other than UTC")
    void noManifestPinsAnotherZone() {
        List<String> offenders = new ArrayList<>();
        for (Path file : manifests()) {
            for (String zone : declaredTimeZones(read(file))) {
                if (!"UTC".equalsIgnoreCase(zone)) {
                    offenders.add(RunOwnedScratch.projectRoot().relativize(file) + " sets TZ=" + zone);
                }
            }
        }
        assertThat(offenders)
                .withFailMessage(() -> "A deployment manifest pins a zone other than UTC, which defeats the "
                        + "Dockerfile's declaration:\n  " + String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("the Dockerfile reader attributes ENV TZ to the stage that declares it")
    void theReaderAttributesEachDeclarationToItsStage() {
        Map<String, String> declared = timeZonePerStage("""
                FROM base AS builder
                RUN true
                FROM base AS production
                ENV TZ=UTC
                FROM base AS development
                ENV TZ="Europe/Berlin"
                """);
        assertThat(declared).containsExactlyInAnyOrderEntriesOf(
                Map.of("production", "UTC", "development", "Europe/Berlin"));
    }

    @Test
    @DisplayName("the Dockerfile reader does not read a TZ set before any stage alias")
    void theReaderIgnoresWhatNoStageOwns() {
        assertThat(timeZonePerStage("ENV TZ=UTC\nFROM base AS production\nRUN true\n")).isEmpty();
    }

    @Test
    @DisplayName("the manifest reader sees every spelling of a container env var")
    void theManifestReaderSeesEverySpelling() {
        assertThat(declaredTimeZones("  TZ: \"Europe/Berlin\"\n")).containsExactly("Europe/Berlin");
        assertThat(declaredTimeZones("      - TZ=Europe/Berlin\n")).containsExactly("Europe/Berlin");
        assertThat(declaredTimeZones("""
                        env:
                          - name: TZ
                            value: "Europe/Berlin"
                """)).containsExactly("Europe/Berlin");
        assertThat(declaredTimeZones("          - {name: TZ, value: Europe/Berlin}\n"))
                .containsExactly("Europe/Berlin");
    }

    @Test
    @DisplayName("a TZ the reader cannot resolve fails closed")
    void anUnresolvableEnvValueIsNotTakenForUtc() {
        assertThat(declaredTimeZones("""
                        env:
                          - name: TZ
                            valueFrom:
                              configMapKeyRef: {name: forge-config, key: TZ}
                """)).containsExactly(UNRESOLVED);
    }

    @Test
    @DisplayName("the manifest reader does not read a neighbouring env var as TZ")
    void theManifestReaderIgnoresOtherEnvVars() {
        assertThat(declaredTimeZones("""
                        env:
                          - name: TZDATA_PATH
                            value: /usr/share/zoneinfo
                          - name: SPRING_PROFILES_ACTIVE
                            value: prod
                """)).isEmpty();
    }

    /**
     * Every time zone a manifest pins, in all three spellings a container env var takes.
     *
     * <p>The two-line Kubernetes form is the one that matters: it is how a {@code Deployment} sets
     * an env var, it wins over the image's {@code ENV}, and a reader that only understands
     * {@code KEY: value} would pass it silently — a guard blind to the commonest spelling of the
     * thing it forbids. An env entry whose value is not a literal ({@code valueFrom}) reads as
     * {@link #UNRESOLVED} rather than as absent, so the guard fails closed on what it cannot
     * prove.</p>
     */
    static List<String> declaredTimeZones(String yaml) {
        List<String> zones = new ArrayList<>();
        Matcher assignment = YAML_TZ_ASSIGNMENT.matcher(yaml);
        while (assignment.find()) {
            zones.add(assignment.group(1));
        }
        Matcher inline = YAML_TZ_ENV_INLINE.matcher(yaml);
        while (inline.find()) {
            zones.add(inline.group(1));
        }
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!YAML_TZ_ENV_NAME.matcher(lines[i]).matches()) {
                continue;
            }
            // The value is the sibling key of the same list entry, so it is the next non-blank
            // line; anything that is not a literal `value:` leaves the zone unresolved.
            String value = UNRESOLVED;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].isBlank()) {
                    continue;
                }
                Matcher matcher = YAML_ENV_VALUE.matcher(lines[j]);
                if (matcher.matches()) {
                    value = matcher.group(1);
                }
                break;
            }
            zones.add(value);
        }
        return zones;
    }

    /** Stage alias → the zone that stage's last {@code ENV TZ} declares. */
    private static Map<String, String> timeZonePerStage(String dockerfile) {
        Map<String, String> byStage = new LinkedHashMap<>();
        Matcher from = FROM.matcher(dockerfile);
        List<int[]> bounds = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        while (from.find()) {
            if (!bounds.isEmpty()) {
                bounds.get(bounds.size() - 1)[1] = from.start();
            }
            bounds.add(new int[]{from.end(), dockerfile.length()});
            aliases.add(from.group(1));
        }
        for (int i = 0; i < bounds.size(); i++) {
            if (aliases.get(i) == null) {
                continue;
            }
            Matcher env = ENV_TZ.matcher(dockerfile.substring(bounds.get(i)[0], bounds.get(i)[1]));
            while (env.find()) {
                byStage.put(aliases.get(i), env.group(1));
            }
        }
        return byStage;
    }

    private static List<Path> manifests() {
        List<Path> files = new ArrayList<>();
        Path root = RunOwnedScratch.projectRoot();
        try (Stream<Path> walk = Files.walk(root.resolve("k8s"))) {
            walk.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk k8s/", e);
        }
        try (Stream<Path> top = Files.list(root)) {
            top.filter(p -> p.getFileName().toString().startsWith("docker-compose")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the project root", e);
        }
        assertThat(files).hasSizeGreaterThan(5);
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
