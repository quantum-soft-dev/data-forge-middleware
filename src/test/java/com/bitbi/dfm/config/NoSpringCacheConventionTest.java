package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application has no Spring Cache and no Redis, and a declaration of either is a decision
 * rather than a line somebody adds (issue #319).
 *
 * <p>The only caches this application ever declared were the two Upload History ones, and neither
 * held a value for the life of the feature: {@code batch-details} was conditioned on
 * {@code #result}, which a {@code condition} evaluates before the call, where it is undefined, and
 * {@code batch-first-page} sat on a method reached through {@code this}, which the proxy never
 * sees. Making them live was not the fix either — the detail key was the batch id alone while the
 * owner check ran inside the method, so a live entry would have served one account's batch to any
 * account that asked for its id. They were removed, and Redis with them, since nothing else used
 * it.</p>
 *
 * <p>What this pins is the failure the removal ended: an annotation that <em>reads</em> as caching
 * while doing nothing, and a runtime dependency on a store that holds nothing. A cache annotation
 * with no {@code @EnableCaching} is inert, and one with it needs a store; either way, adding one
 * back needs the design this ticket did not find — a key that carries the caller, eviction on
 * delete, retention and wipe — so it is refused here by name rather than left to review.</p>
 */
class NoSpringCacheConventionTest {

    private static final Pattern CACHE_ANNOTATION = Pattern.compile(
            "@(?:org\\.springframework\\.cache\\.annotation\\.)?"
                    + "(Cacheable|CachePut|CacheEvict|Caching|CacheConfig|EnableCaching)\\b");

    private static final Pattern REDIS_DEPENDENCY = Pattern.compile(
            "spring-boot-starter-(?:data-redis|cache)\\b|spring-data-redis\\b|\\blettuce-core\\b|\\bjedis\\b");

    private static final Pattern REDIS_KEY = Pattern.compile("(?m)^\\s*(redis|cache)\\s*:");

    @Test
    @DisplayName("no production class declares a Spring Cache annotation")
    void noCacheAnnotationInProductionCode() {
        List<String> found = new ArrayList<>();
        for (Path file : files("src/main/java", ".java")) {
            found.addAll(scan(relative(file), read(file)));
        }
        assertThat(found)
                .withFailMessage("Spring Cache is not part of this application (#319), but found:%n%s",
                        String.join("\n", found))
                .isEmpty();
    }

    @Test
    @DisplayName("the build declares no Redis client and no cache starter")
    void noRedisDependencyInTheBuild() {
        String build = withoutLineComments(read(RunOwnedScratch.projectRoot().resolve("build.gradle.kts")),
                "//");
        Matcher matcher = REDIS_DEPENDENCY.matcher(build);
        String declared = matcher.find() ? matcher.group() : null;
        assertThat(declared)
                .withFailMessage("build.gradle.kts declares %s; nothing in this application uses Redis "
                        + "or Spring Cache (#319)", declared)
                .isNull();
    }

    @Test
    @DisplayName("no Redis client is on the classpath, so no health contributor can depend on one")
    void noRedisClientOnTheClasspath() {
        assertThat(ClassUtils.isPresent(
                "org.springframework.data.redis.connection.RedisConnectionFactory", null))
                .withFailMessage("Spring Data Redis is on the classpath: Boot would auto-configure a "
                        + "connection factory and a redis health contributor for a store nothing uses")
                .isFalse();
    }

    @Test
    @DisplayName("no shipped configuration carries a spring.data.redis or spring.cache block")
    void noRedisOrCacheConfiguration() {
        List<String> found = new ArrayList<>();
        for (Path file : files("src/main/resources", ".yml")) {
            Matcher matcher = REDIS_KEY.matcher(withoutLineComments(read(file), "#"));
            while (matcher.find()) {
                found.add(relative(file) + ": " + matcher.group(1) + ":");
            }
        }
        assertThat(found)
                .withFailMessage("Configuration for a cache or a Redis this application does not have:%n%s",
                        String.join("\n", found))
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds an annotation in code and ignores one named in prose or a literal")
    void scanReadsCodeOnly() {
        assertThat(scan("A.java", """
                class A {
                    @Cacheable("x") void a() {}
                    @org.springframework.cache.annotation.CacheEvict(allEntries = true) void b() {}
                }
                """)).containsExactly("A.java:2 @Cacheable", "A.java:3 @CacheEvict");
        assertThat(scan("B.java", """
                /** Not {@code @Cacheable}: see #319. */
                class B {
                    // @EnableCaching was removed
                    String reason = "no @Cacheable here";
                }
                """)).isEmpty();
    }

    static List<String> scan(String path, String source) {
        AsyncExecutorQualifierTest.Stripped stripped = AsyncExecutorQualifierTest.strip(source);
        String code = stripped.code();
        List<String> found = new ArrayList<>();
        Matcher matcher = CACHE_ANNOTATION.matcher(code);
        while (matcher.find()) {
            if (!stripped.insideLiteral()[matcher.start()]) {
                found.add(path + ":" + lineOf(code, matcher.start()) + " @" + matcher.group(1));
            }
        }
        return found;
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String withoutLineComments(String text, String marker) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            kept.append(line.stripLeading().startsWith(marker) ? "" : line).append('\n');
        }
        return kept.toString();
    }

    private static List<Path> files(String root, String suffix) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(RunOwnedScratch.projectRoot().resolve(root))) {
            walk.filter(p -> p.toString().endsWith(suffix)).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + root, e);
        }
        assertThat(files).withFailMessage("No %s files under %s — the scan has gone blind", suffix, root)
                .isNotEmpty();
        return files;
    }

    private static String relative(Path file) {
        return RunOwnedScratch.projectRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
