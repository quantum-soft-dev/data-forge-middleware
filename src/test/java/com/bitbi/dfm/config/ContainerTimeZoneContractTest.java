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
 * <p>Every zone-independent {@code TIMESTAMP} column in this schema holds a UTC wall clock, and the
 * two ways of reading one — raw JDBC, which takes the column as it is, and Hibernate, which
 * converts through the JVM's zone because of {@code hibernate.jdbc.time_zone: UTC} — give the same
 * answer on one condition: that zone is UTC. Until this guard the condition held because the base
 * image happens to default to UTC, which is a property of a third party's image and of nothing this
 * repository says.</p>
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
    /** {@code TZ: "UTC"} / {@code TZ=UTC} / {@code - name: TZ} + {@code value: …} in YAML. */
    private static final Pattern YAML_TZ = Pattern.compile(
            "(?m)^\\s*-?\\s*(?:name:\\s*)?[\"']?TZ[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9/_+-]+)[\"']?\\s*$");

    @Test
    @DisplayName("every runtime stage of the Dockerfile sets TZ=UTC")
    void everyRuntimeStageDeclaresUtc() {
        Map<String, String> declared = timeZonePerStage(read(RunOwnedScratch.projectRoot().resolve("Dockerfile")));
        for (String stage : RUNTIME_STAGES) {
            assertThat(declared)
                    .withFailMessage("Dockerfile stage '%s' must declare ENV TZ=UTC: the Hibernate and raw-JDBC "
                            + "reads of a TIMESTAMP column agree only in UTC (#280, see README.md \"Time zones\"). "
                            + "Declared zones per stage: %s", stage, declared)
                    .containsEntry(stage, "UTC");
        }
    }

    @Test
    @DisplayName("no manifest overrides the zone with something other than UTC")
    void noManifestPinsAnotherZone() {
        List<String> offenders = new ArrayList<>();
        for (Path file : manifests()) {
            Matcher matcher = YAML_TZ.matcher(read(file));
            while (matcher.find()) {
                if (!"UTC".equalsIgnoreCase(matcher.group(1))) {
                    offenders.add(RunOwnedScratch.projectRoot().relativize(file) + " sets TZ=" + matcher.group(1));
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
