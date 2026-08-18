package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #195 — every {@code @Async} site names the executor it runs on.
 *
 * <p>{@code @Async} without a qualifier does not fail the build, and it does not fail at startup
 * either. It falls back to {@code AsyncExecutionAspectSupport}'s default resolution: Boot's
 * {@code applicationTaskExecutor} if one exists, otherwise a {@code SimpleAsyncTaskExecutor} that
 * starts a <b>new thread per invocation</b> with no ceiling at all. Boot's
 * {@code TaskExecutionAutoConfiguration#applicationTaskExecutor} is
 * {@code @ConditionalOnMissingBean(Executor.class)} and this application declares several
 * {@link Executor} beans, so it backs off — which makes the unbounded fallback the live branch
 * here, not the theoretical one. {@link #theExecutorBeanScanAgreesWithTheConnectionAudit()} asserts
 * that premise rather than leaving it as prose.</p>
 *
 * <p>An unbounded per-invocation executor is exactly the shape {@code BackgroundConnectionDemandTest}
 * (#161) exists to keep out, and it is invisible to all three of that class's discovery routes: it
 * is not a {@code @Bean}, not a {@code max-concurrent} key, and not a
 * {@code new ThreadPoolTaskExecutor(...)} in production source. So the guard belongs here, on the
 * annotation, rather than there.</p>
 *
 * <p>A <em>named</em> executor that does not exist is the same class of defect one step further
 * along: an unknown qualifier is not a startup failure either — {@code AsyncExecutionAspectSupport}
 * resolves it lazily and throws on the <b>first invocation</b> of the method, which for the paths
 * in this application (a plugin dispatch, an audit write) is a runtime failure on a code path a
 * test may never take. Hence the second assertion.</p>
 *
 * <h2>Two scans, because neither reaches everything</h2>
 *
 * <p>{@link #scanSource()} reads {@code src/main/java} as text with comments stripped, which is
 * total over the code this repository owns — no class needs to be concrete, independent or
 * component-scannable to be seen. {@link #scanAnnotations()} loads the production classes and reads
 * the resolved annotation, which is the only way to see a value that is not a string literal, and
 * the only way to see an {@code @Async} that arrives through a meta-annotation. Every site either
 * scan finds goes through both assertions, and
 * {@link #theTwoScansSeeTheSameFiles()} fails when they stop agreeing on which files carry one —
 * a scan that has gone blind is otherwise indistinguishable from a clean application.</p>
 *
 * @see BackgroundConnectionDemandTest
 * @see ScheduledTaskInventoryTest
 */
@DisplayName("Async executor qualifiers (#195)")
class AsyncExecutorQualifierTest {

    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src/main/java");

    private static final String BASE_PACKAGE = "com.bitbi.dfm";

    /**
     * One {@code @Async} in production code.
     *
     * @param location     where it is written, in whatever terms the scan that found it can offer
     * @param executorName the executor bean it names, or {@code null} when it names none
     * @param resolved     whether {@code executorName} is the actual bean name; {@code false} for a
     *                     source-scanned site whose argument is an expression rather than a string
     *                     literal, which the text scan can see is <em>named</em> but cannot evaluate
     */
    private record AsyncSite(String location, String executorName, boolean resolved) {

        boolean namesAnExecutor() {
            return executorName != null && !executorName.isBlank();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The two assertions the issue asks for
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("every @Async in production code names the executor it runs on")
    void everyAsyncSiteNamesAnExecutor() {
        List<AsyncSite> sites = allSites();

        assertFalse(sites.isEmpty(),
                "no @Async was found at all — both scans are broken, not the application");
        List<String> unnamed = sites.stream()
                .filter(site -> !site.namesAnExecutor())
                .map(AsyncSite::location)
                .toList();

        assertTrue(unnamed.isEmpty(),
                "these @Async sites name no executor: " + unnamed + ". An unqualified @Async does "
                        + "not fail the build or the startup — it falls back to a SimpleAsyncTaskExecutor "
                        + "that starts a new thread per invocation with no ceiling, because Boot's "
                        + "applicationTaskExecutor backs off in the presence of the Executor beans this "
                        + "application declares. That is unbounded background demand on a connection "
                        + "pool sized against a counted inventory (issue #161), and it is invisible to "
                        + "every route BackgroundConnectionDemandTest discovers pools by. Name one of "
                        + "the declared executors: " + declaredExecutorBeanNames());
    }

    @Test
    @DisplayName("every @Async names an executor bean this application actually declares")
    void everyAsyncQualifierNamesADeclaredExecutorBean() {
        Set<String> declared = declaredExecutorBeanNames();
        List<String> unknown = allSites().stream()
                .filter(AsyncSite::namesAnExecutor)
                .filter(AsyncSite::resolved)
                .filter(site -> !declared.contains(site.executorName()))
                .map(site -> site.location() + " -> \"" + site.executorName() + "\"")
                .toList();

        assertTrue(unknown.isEmpty(),
                "these @Async sites name an executor bean that is not declared: " + unknown
                        + ". A qualifier naming no bean is not a startup failure: it is resolved on the "
                        + "first invocation of the method and throws there, on a path a test may never "
                        + "take. The declared executors are " + declared + " — if the newcomer is a "
                        + "legitimate executor declared some other way than a @Bean method returning an "
                        + "Executor, it is also invisible to BackgroundConnectionDemandTest, so make it "
                        + "visible to both rather than teaching this scan alone");
    }

    // ---------------------------------------------------------------------------------------
    // Neither scan may go blind
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the source scan and the annotation scan see the same files")
    void theTwoScansSeeTheSameFiles() {
        Set<String> inSource = new TreeSet<>(scanSource().keySet());
        Set<String> inAnnotations = new TreeSet<>(scanAnnotations().keySet());

        assertFalse(inSource.isEmpty(), "the source scan found no @Async — it is broken");
        assertFalse(inAnnotations.isEmpty(), "the annotation scan found no @Async — it is broken");
        assertEquals(inSource, inAnnotations,
                "the two scans disagree about which files carry an @Async. A file only the source "
                        + "scan sees is one whose class the classpath scan cannot reach (an interface, "
                        + "an abstract class, a class it will not load) — the site is real and needs "
                        + "checking by hand. A file only the annotation scan sees carries an @Async that "
                        + "is not written as one, i.e. through a meta-annotation — which the text scan "
                        + "will keep missing for every future site too. Either way, decide before "
                        + "silencing this");
    }

    @Test
    @DisplayName("the executor beans this test resolves names from are the ones the connection audit counts")
    void theExecutorBeanScanAgreesWithTheConnectionAudit() {
        Map<String, String> byName = declaredExecutorBeans();

        assertFalse(byName.isEmpty(),
                "no @Bean returning an Executor was found. Beyond breaking this scan, that would "
                        + "change the premise above: with no Executor bean declared, Boot's "
                        + "applicationTaskExecutor no longer backs off and an unqualified @Async lands "
                        + "on a bounded pool rather than on an unbounded SimpleAsyncTaskExecutor");
        assertEquals(BackgroundConnectionDemandTest.scanExecutorBeans(),
                new TreeSet<>(byName.values()),
                "this class and BackgroundConnectionDemandTest (#161) disagree about which @Bean "
                        + "methods declare an executor. They are two readings of the same set — one for "
                        + "the names an @Async may use, one for the threads the connection pool is sized "
                        + "against — and a pool visible to one but not the other is exactly the gap both "
                        + "were written to close");
    }

    // ---------------------------------------------------------------------------------------
    // The source parser, asserted on its own rather than only through the application
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the parser reports an unqualified @Async as naming nothing")
    void shouldReportAnUnqualifiedAsync() {
        List<AsyncSite> sites = parse("Some.java", """
                class Some {
                    @Async
                    public void run() {
                    }
                }
                """);

        assertEquals(1, sites.size());
        assertFalse(sites.get(0).namesAnExecutor());
    }

    @Test
    @DisplayName("the parser reports an empty qualifier as naming nothing")
    void shouldReportAnEmptyQualifier() {
        List<AsyncSite> sites = parse("Some.java", "@Async(\"\") void run() {}");

        assertEquals(1, sites.size());
        assertFalse(sites.get(0).namesAnExecutor());
    }

    @Test
    @DisplayName("the parser reads the qualifier of a named @Async, written either way")
    void shouldReadANamedQualifier() {
        List<AsyncSite> shorthand = parse("Some.java", "@Async(\"pluginExecutor\") void run() {}");
        List<AsyncSite> explicit = parse("Some.java", "@Async(value = \"pluginExecutor\") void run() {}");

        assertEquals("pluginExecutor", shorthand.get(0).executorName());
        assertTrue(shorthand.get(0).resolved());
        assertEquals("pluginExecutor", explicit.get(0).executorName());
        assertTrue(explicit.get(0).resolved());
    }

    @Test
    @DisplayName("the parser sees a fully qualified annotation")
    void shouldSeeAFullyQualifiedAnnotation() {
        List<AsyncSite> sites = parse("Some.java",
                "@org.springframework.scheduling.annotation.Async(\"pluginExecutor\") void run() {}");

        assertEquals(1, sites.size());
        assertEquals("pluginExecutor", sites.get(0).executorName());
    }

    @Test
    @DisplayName("the parser marks a qualifier it cannot evaluate as named but unresolved")
    void shouldMarkAnExpressionQualifierUnresolved() {
        List<AsyncSite> sites = parse("Some.java", "@Async(Pools.PLUGIN) void run() {}");

        assertEquals(1, sites.size());
        assertTrue(sites.get(0).namesAnExecutor());
        assertFalse(sites.get(0).resolved(),
                "an expression is named but not evaluable from text; the annotation scan is what "
                        + "resolves it, and it is the reason a site is checked by both");
    }

    @Test
    @DisplayName("the parser ignores @Async written in a comment")
    void shouldIgnoreAnAsyncInAComment() {
        List<AsyncSite> sites = parse("Some.java", """
                /**
                 * Not a site: {@code @Async} in prose, and this class has many.
                 */
                // @Async
                class Some {
                }
                """);

        assertTrue(sites.isEmpty());
    }

    @Test
    @DisplayName("the parser does not read a method's own parentheses as a qualifier")
    void shouldNotReadTheMethodSignatureAsAQualifier() {
        List<AsyncSite> sites = parse("Some.java", """
                @Async
                public void run(String pluginExecutor) {
                }
                """);

        assertEquals(1, sites.size());
        assertFalse(sites.get(0).namesAnExecutor(),
                "the argument list belongs to the method, not to the annotation");
    }

    // ---------------------------------------------------------------------------------------
    // Scans
    // ---------------------------------------------------------------------------------------

    private static List<AsyncSite> allSites() {
        List<AsyncSite> sites = new ArrayList<>();
        scanSource().values().forEach(sites::addAll);
        scanAnnotations().values().forEach(sites::addAll);
        return sites;
    }

    /**
     * {@code @Async} as written, keyed by source file relative to {@code src/main/java}.
     *
     * <p>Text rather than reflection so that nothing has to be loadable, concrete or
     * component-scannable to be seen — the classpath scan behind {@link #scanAnnotations()} skips
     * interfaces and abstract classes, and an {@code @Async} on either is still honoured by Spring
     * on the concrete class that inherits it.</p>
     */
    private static Map<String, List<AsyncSite>> scanSource() {
        Map<String, List<AsyncSite>> found = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = PRODUCTION_SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                List<AsyncSite> sites = parse(relative, Files.readString(file));
                if (!sites.isEmpty()) {
                    found.put(relative, sites);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /**
     * {@code @Async} as Spring resolves it, keyed by the source file of the class that carries it.
     *
     * <p>Keyed by file rather than by class so it can be compared with the text scan, and a nested
     * class maps to the file of its outer one for the same reason. Both the method annotation and
     * the type-level one are read: a type-level {@code @Async} makes every method of the class
     * asynchronous, so an unqualified one there is the same defect at a larger radius.</p>
     */
    private static Map<String, List<AsyncSite>> scanAnnotations() {
        Map<String, List<AsyncSite>> found = new TreeMap<>();
        for (Class<?> type : productionClasses()) {
            List<AsyncSite> sites = new ArrayList<>();
            qualifierOf(MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY))
                    .ifPresent(value -> sites.add(site(type.getName(), value)));
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                qualifierOf(MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY))
                        .ifPresent(value ->
                                sites.add(site(type.getName() + "#" + method.getName(), value)));
            }
            if (!sites.isEmpty()) {
                found.computeIfAbsent(sourceFileOf(type), file -> new ArrayList<>()).addAll(sites);
            }
        }
        return found;
    }

    private static java.util.Optional<String> qualifierOf(MergedAnnotations annotations) {
        return annotations.isPresent(Async.class)
                ? java.util.Optional.of(annotations.get(Async.class).getString("value"))
                : java.util.Optional.empty();
    }

    private static AsyncSite site(String location, String value) {
        return new AsyncSite(location, value.isBlank() ? null : value, true);
    }

    /** Names under which this application declares an {@link Executor}, and where each is declared. */
    private static Map<String, String> declaredExecutorBeans() {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Class<?> type : productionClasses()) {
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (!method.isAnnotationPresent(Bean.class)
                        || !Executor.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                String[] names = MergedAnnotations.from(method).get(Bean.class).getStringArray("name");
                String declaredAt = type.getName() + "#" + method.getName();
                if (names.length == 0) {
                    byName.put(method.getName(), declaredAt);
                }
                for (String name : names) {
                    byName.put(name, declaredAt);
                }
            }
        }
        return byName;
    }

    private static Set<String> declaredExecutorBeanNames() {
        return new TreeSet<>(declaredExecutorBeans().keySet());
    }

    /**
     * {@code @Async} occurrences in one Java source, with comments removed first so the prose about
     * this very rule — of which the configuration classes carry a good deal — is not counted.
     *
     * <p>It is the half of this class that can be asserted directly, which is what keeps the file
     * scan above from passing vacuously: a guard that only reads an already-clean application
     * cannot tell "nothing to find" from "found nothing".</p>
     */
    private static List<AsyncSite> parse(String location, String source) {
        String code = JAVA_COMMENT.matcher(source).replaceAll("");
        List<AsyncSite> sites = new ArrayList<>();
        Matcher matcher = ASYNC_ANNOTATION.matcher(code);
        while (matcher.find()) {
            String argument = matcher.group(1);
            if (argument == null) {
                sites.add(new AsyncSite(location, null, true));
                continue;
            }
            String value = argument.trim().replaceFirst("^value\\s*=\\s*", "").trim();
            Matcher literal = STRING_LITERAL.matcher(value);
            if (literal.matches()) {
                String name = literal.group(1);
                sites.add(new AsyncSite(location, name.isBlank() ? null : name, true));
            } else {
                // Named, but not by anything this scan can evaluate. scanAnnotations() resolves it.
                sites.add(new AsyncSite(location, value, false));
            }
        }
        return sites;
    }

    /**
     * {@code @Async}, with its argument list only when the parenthesis is the next thing written —
     * otherwise the parameter list of the annotated method would be read as the qualifier.
     */
    private static final Pattern ASYNC_ANNOTATION = Pattern.compile(
            "@(?:org\\.springframework\\.scheduling\\.annotation\\.)?Async\\b\\s*(?:\\(([^)]*)\\))?");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** Line and block comments, so prose about {@code @Async} is not counted as a site. */
    private static final Pattern JAVA_COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** The file a class is written in, with a nested class mapped to its outer class's file. */
    private static String sourceFileOf(Class<?> type) {
        String topLevel = type.getName().split("\\$", 2)[0];
        return topLevel.replace('.', '/') + ".java";
    }

    private static List<Class<?>> productionClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                // Loaded without initialization: reading annotations must not run static blocks.
                type = Class.forName(className, false,
                        AsyncExecutorQualifierTest.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (isProductionClass(type)) {
                types.add(type);
            }
        }
        return types;
    }

    /** Where this test's own classes live; everything else on the classpath is production. */
    private static final java.net.URL TEST_OUTPUT_ROOT =
            AsyncExecutorQualifierTest.class.getProtectionDomain().getCodeSource().getLocation();

    private static boolean isProductionClass(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation() != null
                && !source.getLocation().equals(TEST_OUTPUT_ROOT);
    }
}
