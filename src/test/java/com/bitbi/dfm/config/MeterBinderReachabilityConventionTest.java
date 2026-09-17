package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gauge's weak target is held past the last read of that gauge (issue #316).
 *
 * <p><strong>What broke.</strong> {@code Gauge.builder(name, target, fn)} keeps {@code target}
 * behind a {@link WeakReference}, so the registry never keeps alive the object it reads. A test
 * that wrote {@code new SomeMetrics(...).bindTo(registry)} dropped the only strong reference on the
 * same line, and from the next collection onwards every gauge built from it read {@code NaN}. Alone
 * the test passed; inside {@code ./gradlew integrationTest} — one JVM, some 2900 tests, plenty of
 * collections — it failed as {@code expected: <42.0> but was: <NaN>} on a diff that had touched
 * none of it. A gate that is red for a reason unrelated to the change is the #207/#226 class of
 * defect: the failure names an innocent test and costs a full investigation.</p>
 *
 * <p><strong>What the scan bans, and why only that.</strong> A chained {@code .bindTo(} — one whose
 * receiver is a call rather than a name — is wrong unconditionally: {@code bindTo} returns
 * {@code void}, so the binder is unreachable the instant the call returns, and no liveness subtlety
 * is needed to explain it. The property that actually matters is wider — the weak target must be
 * strongly reachable at the <em>last</em> gauge read — and that one is not statically decidable:
 * HotSpot may collect an object whose local variable is still in scope but never read again. So the
 * scan closes the shape that is always wrong, and the wider property is held by hand where it
 * arises, each site saying so:</p>
 * <ul>
 *   <li>{@code BatchParquetQueueMetricsTest}, {@code CheckpointGivenUpMetricsTest} and
 *       {@code EgressPendingMetricsTest} keep the binder in a field of the test class — the test
 *       instance is reachable from the running frame for the whole method, which a local is not.</li>
 *   <li>{@code ParquetScratchBudgetTest} is the same hazard through a different registration: its
 *       gauge's weak target is an {@code AtomicLong} field of the budget, and the budget is held in
 *       a field for the same reason.</li>
 *   <li>{@code SqlGenerationConcurrencyTest} reads {@code sql.generation.semaphore.queue.size},
 *       registered through {@code meterRegistry.gauge(name, target, fn)} — the same weak hold, one
 *       registration form over, and therefore invisible to the scan below, since nothing is
 *       chained. {@code awaitSemaphoreQueueSize} polls while other threads still hold the service,
 *       so it needs nothing; its {@code SemaphoreMetrics} methods do not, and hold the service in a
 *       field. {@code SqlGenerationStreamingTest} asserts only that the gauge exists and never
 *       reads its value, which the weak reference cannot affect.</li>
 *   <li>{@code ComparisonMetrics} has no test, and in production its weak target is a repository
 *       bean the application context holds.</li>
 * </ul>
 *
 * <p>Production binders are Spring beans held by the context, so {@code src/main} is scanned only to
 * keep the rule one rule: a chained {@code bindTo} there would be no better.</p>
 */
@DisplayName("A gauge's weak target is held past the last read of that gauge")
class MeterBinderReachabilityConventionTest {

    private static final String METER_NAME = "test.meter-binder.reachability";

    /** {@code .bindTo(} together with whatever immediately precedes the dot. */
    private static final Pattern BIND_TO = Pattern.compile("(\\S)\\s*\\.\\s*bindTo\\s*\\(");

    private static final String REMEDY =
            "assign the binder to a field of the test class first and bind through it — "
                    + "Gauge.builder(name, target, fn) holds the target weakly, so a binder nobody "
                    + "references reads NaN from the next collection onwards. See #316.";

    /** One chained {@code bindTo} in real code. */
    record Violation(String path, int line) {
        @Override
        public String toString() {
            return path + ":" + line + " binds a MeterBinder nobody keeps a reference to";
        }
    }

    @Test
    @DisplayName("no source binds a MeterBinder it keeps no reference to")
    void noSourceBindsAnUnreferencedMeterBinder() {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            violations.addAll(scan(relative(file), read(file)));
        }
        assertThat(violations)
                .withFailMessage(() -> "These sources bind a MeterBinder that is unreachable the "
                        + "moment bindTo returns:\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nTo fix: " + REMEDY)
                .isEmpty();
    }

    @Test
    @DisplayName("a gauge reads its held target, and NaN once the unheld one is collected")
    void aGaugeReadsItsHeldTargetAndNaNOnceTheUnheldOneIsCollected() throws InterruptedException {
        // The wired half: without it the ban above is a rule whose reason nobody can check, and a
        // Micrometer release that began holding the target strongly would leave it standing for
        // nothing. Both directions are asserted over one registry so neither can pass vacuously.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Probe held = new Probe(7);
        held.bindTo(registry, "held");
        Probe unheld = new Probe(9);
        unheld.bindTo(registry, "unheld");
        unheld = null;

        forceWeakReferenceClearing();

        assertThat(gauge(registry, "unheld").value())
                .as("a binder nobody references is gone, and its gauge has nothing left to read")
                .isNaN();
        assertThat(gauge(registry, "held").value())
                .as("a binder that is still reachable still answers")
                .isEqualTo(7.0);
        // Not decoration: `held` is never read after bindTo, so without this fence the method would
        // be the very defect it documents — a local in scope that HotSpot is free to collect. A
        // field, which is what the fixed tests use, needs no fence.
        Reference.reachabilityFence(held);
    }

    @Test
    @DisplayName("the scan names a chained bindTo and leaves a held one alone")
    void theScanTellsAChainedBindToFromAHeldOne() {
        assertThat(scan("X.java", """
                class X {
                    void bad(MeterRegistry registry) {
                        new EgressPendingMetrics(repository).bindTo(registry);
                        metrics(Duration.ofSeconds(30)).bindTo(registry);
                    }

                    void good(MeterRegistry registry) {
                        binder = new EgressPendingMetrics(repository);
                        binder.bindTo(registry);
                        this.binder.bindTo(registry);
                    }
                }
                """))
                .extracting(Violation::line)
                .containsExactly(3, 4);
    }

    @Test
    @DisplayName("the scan reads code and not prose")
    void theScanIgnoresCommentsAndStringLiterals() {
        assertThat(scan("X.java", """
                class X {
                    // new Metrics(repo).bindTo(registry)
                    /* new Metrics(repo).bindTo(registry) */
                    /** {@code new Metrics(repo).bindTo(registry)} */
                    String s = "new Metrics(repo).bindTo(registry)";
                    public void bindTo(MeterRegistry registry) {
                    }
                }
                """)).isEmpty();
    }

    /** Every chained {@code bindTo} in {@code source}, comments and string literals excluded. */
    static List<Violation> scan(String path, String source) {
        AsyncExecutorQualifierTest.Stripped stripped = AsyncExecutorQualifierTest.strip(source);
        String code = stripped.code();
        List<Violation> found = new ArrayList<>();
        Matcher matcher = BIND_TO.matcher(code);
        while (matcher.find()) {
            if (stripped.insideLiteral()[matcher.start()] || matcher.group(1).charAt(0) != ')') {
                continue;
            }
            found.add(new Violation(path, lineOf(code, matcher.start())));
        }
        return found;
    }

    /**
     * Collects every unreachable object, proven rather than assumed.
     *
     * <p>A bare {@code System.gc()} is a hint, so the loop waits for a witness — a weak reference to
     * an object nothing can reach — to be cleared. Without that witness both assertions above would
     * pass on a JVM that collected nothing, which is the vacuous green this class exists to
     * remove.</p>
     */
    private static void forceWeakReferenceClearing() throws InterruptedException {
        Object witness = new Object();
        WeakReference<Object> reference = new WeakReference<>(witness);
        witness = null;
        for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertThat(reference.get())
                .as("this JVM cleared no weak reference in half a second — is -XX:+DisableExplicitGC set?")
                .isNull();
    }

    private static Gauge gauge(MeterRegistry registry, String kind) {
        return registry.get(METER_NAME).tag("kind", kind).gauge();
    }

    /** A binder whose gauge reads the binder itself — the shape every binder in this repository uses. */
    private static final class Probe implements MeterBinder {

        private final long value;

        private Probe(long value) {
            this.value = value;
        }

        @Override
        public void bindTo(MeterRegistry registry) {
            bindTo(registry, "held");
        }

        private void bindTo(MeterRegistry registry, String kind) {
            Gauge.builder(METER_NAME, this, Probe::read).tag("kind", kind).register(registry);
        }

        private double read() {
            return value;
        }
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

    private static List<Path> javaSources() {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path dir = RunOwnedScratch.projectRoot().resolve(root);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot walk " + dir, e);
            }
        }
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
