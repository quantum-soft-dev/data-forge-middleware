package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.TaskScheduler;
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
import java.util.function.Supplier;
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
 * scan finds goes through both assertions, and {@link #theTwoScansSeeTheSameSites()} fails when
 * they stop agreeing — a scan that has gone blind is otherwise indistinguishable from a clean
 * application.</p>
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
     * @param file         the source file it is written in, relative to {@code src/main/java}
     * @param location     where it is, in whatever terms the scan that found it can offer
     * @param executorName the executor bean it names, or {@code null} when it names none
     * @param resolved     whether {@code executorName} is the actual bean name; {@code false} for a
     *                     source-scanned site whose argument is an expression rather than a string
     *                     literal, which the text scan can see is <em>named</em> but cannot evaluate
     */
    private record AsyncSite(String file, String location, String executorName, boolean resolved,
                            boolean comparable) {

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
                .distinct()
                .toList();

        assertTrue(unnamed.isEmpty(),
                () -> "these @Async sites name no executor: " + unnamed + ". An unqualified @Async does "
                        + "not fail the build or the startup — it falls back to a SimpleAsyncTaskExecutor "
                        + "that starts a new thread per invocation with no ceiling, because Boot's "
                        + "applicationTaskExecutor backs off in the presence of the Executor beans this "
                        + "application declares. That is unbounded background demand on a connection "
                        + "pool sized against a counted inventory (issue #161), and it is invisible to "
                        + "every route BackgroundConnectionDemandTest discovers pools by. Name one of "
                        + "the executors an @Async may use: " + nameableExecutorBeans().keySet());
    }

    @Test
    @DisplayName("every @Async names an executor bean this application actually declares")
    void everyAsyncQualifierNamesADeclaredExecutorBean() {
        Map<String, String> nameable = nameableExecutorBeans();
        List<String> unknown = allSites().stream()
                .filter(AsyncSite::namesAnExecutor)
                .filter(AsyncSite::resolved)
                .filter(site -> !nameable.containsKey(site.executorName()))
                .map(site -> site.location() + " -> \"" + site.executorName() + "\"")
                .distinct()
                .toList();

        assertTrue(unknown.isEmpty(),
                () -> "these @Async sites name an executor bean an @Async may not use: " + unknown
                        + ". A qualifier naming no bean is not a startup failure: it is resolved on the "
                        + "first invocation of the method and throws there, on a path a test may never "
                        + "take. The executors available to an @Async are " + nameable.keySet()
                        + " — every @Bean returning an Executor except the TaskScheduler ones, which are "
                        + "excluded deliberately: " + SCHEDULER_EXCLUSION + ". If the newcomer is a "
                        + "legitimate executor declared some other way than a @Bean method returning an "
                        + "Executor, it is also invisible to BackgroundConnectionDemandTest, so make it "
                        + "visible to both rather than teaching this scan alone");
    }

    // ---------------------------------------------------------------------------------------
    // Neither scan may go blind
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the source scan and the annotation scan see the same sites")
    void theTwoScansSeeTheSameSites() {
        Map<String, Integer> inSource = countByFile(comparable(scanSource()));
        Map<String, Integer> inAnnotations = countByFile(comparable(scanAnnotations()));

        assertFalse(inSource.isEmpty(), "the source scan found no @Async — it is broken");
        assertFalse(inAnnotations.isEmpty(), "the annotation scan found no @Async — it is broken");
        assertEquals(inSource, inAnnotations,
                "the two scans disagree about the @Async sites per file (source scan on the left). "
                        + "A file, or a site within one, that only the source scan sees belongs to a "
                        + "class the classpath scan cannot reach (an interface, an abstract class, a "
                        + "class it will not load) — the site is real and needs checking by hand. One "
                        + "only the annotation scan sees is an @Async that is not written as one, i.e. "
                        + "through a meta-annotation — which the text scan will keep missing for every "
                        + "future site too. The count is compared and not just the file, because this "
                        + "application keeps 13 of its 15 sites in a single file: a stripper accident "
                        + "there could hide twelve of them while every other assertion stayed green. "
                        + "Either way, decide before silencing this. (The two shapes only one scan can "
                        + "see by construction are excluded rather than compared: an @Async written on "
                        + "an annotation declaration, which the classpath scan skips because an "
                        + "annotation type is an interface, and the sites that inherit it, which are "
                        + "not written anywhere. Both still go through the two assertions above.)");
    }

    @Test
    @DisplayName("the executor beans this test resolves names from are the ones the connection audit counts")
    void theExecutorBeanScanAgreesWithTheConnectionAudit() {
        List<ExecutorBean> declared = declaredExecutorBeans();

        assertFalse(declared.isEmpty(),
                "no @Bean returning an Executor was found. Beyond breaking this scan, that would "
                        + "change the premise above: with no Executor bean declared, Boot's "
                        + "applicationTaskExecutor no longer backs off and an unqualified @Async lands "
                        + "on a bounded pool rather than on an unbounded SimpleAsyncTaskExecutor");
        Set<String> factories = new TreeSet<>();
        declared.forEach(bean -> factories.add(bean.declaredAt()));

        assertEquals(BackgroundConnectionDemandTest.scanExecutorBeans(), factories,
                "this class and BackgroundConnectionDemandTest (#161) disagree about which @Bean "
                        + "methods declare an executor. They are two readings of the same set — one for "
                        + "the names an @Async may use, one for the threads the connection pool is sized "
                        + "against — and a pool visible to one but not the other is exactly the gap both "
                        + "were written to close");
    }

    @Test
    @DisplayName("no executor bean name is declared twice")
    void everyExecutorBeanNameIsUnique() {
        Map<String, String> byName = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (ExecutorBean bean : distinctExecutorBeans()) {
            for (String name : bean.names()) {
                String previous = byName.put(name, bean.declaredAt());
                if (previous != null) {
                    clashes.add(name + " declared by " + previous + " and " + bean.declaredAt());
                }
            }
        }

        assertTrue(clashes.isEmpty(),
                "two @Bean methods declare the same executor bean name: " + clashes + ". Spring would "
                        + "have one override the other, and the name-keyed lookups above would silently "
                        + "lose an entry — so an @Async naming it would be resolved against a pool "
                        + "nobody chose, and the drift check against BackgroundConnectionDemandTest "
                        + "would fail naming a cause that has nothing to do with either scan");
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

    @Test
    @DisplayName("a string literal containing /* does not swallow the code after it")
    void shouldNotTreatAnAntPatternAsACommentOpener() {
        List<AsyncSite> sites = parse("Some.java", """
                class Some {
                    private static final String PATH = "/api/v1/device/**";

                    @Async("pluginExecutor")
                    void run() {
                    }

                    /** Trailing Javadoc, whose closing marker would end the phantom comment. */
                    void other() {
                    }
                }
                """);

        assertEquals(1, sites.size(),
                "a comment stripper that does not know about string literals reads \"/api/v1/**\" as "
                        + "the start of a block comment and deletes everything up to the next */, which "
                        + "is the whole point of the two scans agreeing site by site");
        assertEquals("pluginExecutor", sites.get(0).executorName());
    }

    @Test
    @DisplayName("an escaped quote and a text block do not desynchronize the stripper")
    void shouldHandleEscapesAndTextBlocks() {
        // Written by concatenation rather than as a text block: the fixture has to contain a text
        // block of its own, and nesting one inside another is unreadable at best.
        String source = "class Some {\n"
                + "    private static final String QUOTED = \"he said \\\"/* \\\" and stopped\";\n"
                + "    private static final String BLOCK = \"\"\"\n"
                + "            a text block with /* and \" inside\n"
                + "            \"\"\";\n"
                + "\n"
                + "    @Async(\"pluginExecutor\")\n"
                + "    void run() {\n"
                + "    }\n"
                + "}\n";

        List<AsyncSite> sites = parse("Some.java", source);

        assertEquals(1, sites.size());
        assertEquals("pluginExecutor", sites.get(0).executorName());
    }

    @Test
    @DisplayName("a character literal holding a quote does not desynchronize the stripper")
    void shouldHandleCharacterLiterals() {
        List<AsyncSite> sites = parse("Some.java", """
                class Some {
                    private static final char QUOTE = '"';

                    @Async("pluginExecutor")
                    void run() {
                    }
                }
                """);

        assertEquals(1, sites.size());
        assertEquals("pluginExecutor", sites.get(0).executorName());
    }

    @Test
    @DisplayName("the parser ignores @Async written inside a string literal")
    void shouldIgnoreAnAsyncInsideAStringLiteral() {
        List<AsyncSite> sites = parse("Some.java", """
                class Some {
                    void log() {
                        log.error("@Async dispatch rejected, entry not written");
                        throw new IllegalStateException("this must not be @Async");
                    }
                }
                """);

        assertTrue(sites.isEmpty(),
                "a log line or an exception message naming @Async is text, not an annotation — and "
                        + "the literals cannot simply be blanked, because the qualifier is one");
    }

    @Test
    @DisplayName("an @Async on an annotation declaration is asserted but not compared")
    void shouldNotCompareAMetaAnnotationDeclaration() {
        List<AsyncSite> sites = parse("BackgroundTask.java", """
                @Async("pluginExecutor")
                public @interface BackgroundTask {
                }
                """);

        assertEquals(1, sites.size());
        assertEquals("pluginExecutor", sites.get(0).executorName());
        assertTrue(comparable(sites).isEmpty(),
                "the classpath scan cannot reach an annotation type, and the methods that carry the "
                        + "meta-annotation have no qualifier written on them, so comparing the two "
                        + "scans site by site would fail on correct code. The qualifier is still "
                        + "required to name a declared executor");
    }

    // ---------------------------------------------------------------------------------------
    // Scans
    // ---------------------------------------------------------------------------------------

    private static List<AsyncSite> allSites() {
        List<AsyncSite> sites = new ArrayList<>(scanSource());
        sites.addAll(scanAnnotations());
        return sites;
    }

    /**
     * The sites both scans can see, which is what {@link #theTwoScansSeeTheSameSites()} may compare.
     *
     * <p>Two shapes are excluded because only one scan can ever see them, and comparing them would
     * fail on correct code. An {@code @Async} written on a <b>meta-annotation</b> is text in the
     * annotation's own file, which the classpath scan skips (an annotation type is an interface);
     * every method carrying that meta-annotation is a resolved site the text scan cannot see,
     * because the qualifier is not written there. Excluding them costs nothing this check is for:
     * both still go through the two assertions above, which is where a nameless or unknown executor
     * is caught.</p>
     */
    private static List<AsyncSite> comparable(List<AsyncSite> sites) {
        return sites.stream().filter(AsyncSite::comparable).toList();
    }

    private static Map<String, Integer> countByFile(List<AsyncSite> sites) {
        Map<String, Integer> counts = new TreeMap<>();
        sites.forEach(site -> counts.merge(site.file(), 1, Integer::sum));
        return counts;
    }

    /**
     * {@code @Async} as written in {@code src/main/java}.
     *
     * <p>Text rather than reflection so that nothing has to be loadable, concrete or
     * component-scannable to be seen — the classpath scan behind {@link #scanAnnotations()} skips
     * interfaces and abstract classes, and an {@code @Async} on either is still honoured by Spring
     * on the concrete class that inherits it.</p>
     */
    private static List<AsyncSite> scanSource() {
        List<AsyncSite> found = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = PRODUCTION_SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                found.addAll(parse(relative, Files.readString(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /**
     * {@code @Async} as Spring resolves it, attributed to the file it is <em>written</em> in.
     *
     * <p>Attribution is by the annotation's own source and not by the class the scan happened to
     * reach it through, because {@code getAllDeclaredMethods} walks the hierarchy: an {@code @Async}
     * on a superclass would otherwise be reported once per concrete subclass, in files that do not
     * contain it, and the site-by-site comparison would fail on correct code. For the same reason
     * the sites are de-duplicated by method signature.</p>
     *
     * <p>Both the method annotation and the type-level one are read: a type-level {@code @Async}
     * makes every method of the class asynchronous, so an unqualified one there is the same defect
     * at a larger radius.</p>
     */
    private static List<AsyncSite> scanAnnotations() {
        Map<String, AsyncSite> bySignature = new TreeMap<>();
        for (Class<?> type : productionClasses()) {
            MergedAnnotation<Async> onType = MergedAnnotations
                    .from(type, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY).get(Async.class);
            if (onType.isPresent()) {
                Class<?> owner = sourceType(onType.getSource(), type);
                bySignature.put(owner.getName(), site(owner, owner.getName(),
                        onType.getString("value"), onType.getDistance() == 0));
            }
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                MergedAnnotation<Async> onMethod = MergedAnnotations
                        .from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY).get(Async.class);
                if (!onMethod.isPresent()) {
                    continue;
                }
                Method owner = onMethod.getSource() instanceof Method declared ? declared : method;
                bySignature.put(signatureOf(owner),
                        site(owner.getDeclaringClass(),
                                owner.getDeclaringClass().getName() + "#" + owner.getName(),
                                onMethod.getString("value"), onMethod.getDistance() == 0));
            }
        }
        return new ArrayList<>(bySignature.values());
    }

    private static Class<?> sourceType(Object source, Class<?> fallback) {
        if (source instanceof Class<?> type) {
            return type;
        }
        return source instanceof Method method ? method.getDeclaringClass() : fallback;
    }

    private static String signatureOf(Method method) {
        StringBuilder signature = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            signature.append(parameter.getName()).append(',');
        }
        return signature.append(')').toString();
    }

    private static AsyncSite site(Class<?> owner, String location, String value, boolean written) {
        return new AsyncSite(sourceFileOf(owner), location, value.isBlank() ? null : value, true,
                written);
    }

    /**
     * One {@code @Bean} method whose product is an {@link Executor}.
     *
     * @param declaredAt  the {@code Class#method} that declares it, the form
     *                    {@code BackgroundConnectionDemandTest} keys its inventory by
     * @param names       every name the bean is registered under
     * @param isScheduler whether its product is a {@link TaskScheduler}
     */
    private record ExecutorBean(String declaredAt, String signature, List<String> names,
                               boolean isScheduler) {
    }

    /**
     * Why a {@link TaskScheduler} is not a name an {@code @Async} may use. Quoted in the failure
     * message so the exclusion is read where it would bite.
     */
    private static final String SCHEDULER_EXCLUSION =
            "spring.task.scheduling.pool.size is derived (issues #146, #161) over the @Scheduled "
                    + "inventory alone, so async work parked there would postpone the fixed-delay "
                    + "sweeps the pool was sized for, silently and without appearing in any inventory";

    /** Every {@code @Bean} method in production code whose product is an {@link Executor}. */
    private static List<ExecutorBean> declaredExecutorBeans() {
        List<ExecutorBean> beans = new ArrayList<>();
        for (Class<?> type : productionClasses()) {
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (!method.isAnnotationPresent(Bean.class)
                        || !Executor.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                String[] declaredNames =
                        MergedAnnotations.from(method).get(Bean.class).getStringArray("name");
                List<String> names = new ArrayList<>(declaredNames.length == 0
                        ? List.of(method.getName())
                        : List.of(declaredNames));
                // Spring resolves an @Async qualifier through BeanFactoryAnnotationUtils, which
                // matches a @Qualifier on the factory method as well as the bean's own names.
                MergedAnnotation<Qualifier> qualifier =
                        MergedAnnotations.from(method).get(Qualifier.class);
                if (qualifier.isPresent() && !qualifier.getString("value").isBlank()) {
                    names.add(qualifier.getString("value"));
                }
                beans.add(new ExecutorBean(type.getName() + "#" + method.getName(),
                        signatureOf(method), List.copyOf(names),
                        TaskScheduler.class.isAssignableFrom(method.getReturnType())));
            }
        }
        return beans;
    }

    /** The executor bean names an {@code @Async} may name, and where each is declared. */
    private static Map<String, String> nameableExecutorBeans() {
        Map<String, String> byName = new TreeMap<>();
        for (ExecutorBean bean : distinctExecutorBeans()) {
            if (bean.isScheduler()) {
                continue;
            }
            bean.names().forEach(name -> byName.put(name, bean.declaredAt()));
        }
        return byName;
    }

    /**
     * The executor beans by <em>declaration</em>, one entry each.
     *
     * <p>{@link #declaredExecutorBeans()} reports a factory method once per scanned type that
     * inherits it, which is what keeps it comparable with
     * {@code BackgroundConnectionDemandTest.scanExecutorBeans()} — but a {@code @Bean} inherited
     * from a base {@code @Configuration} is one bean Spring registers once, so counting it twice
     * would report a name clash that does not exist.</p>
     */
    private static List<ExecutorBean> distinctExecutorBeans() {
        Map<String, ExecutorBean> bySignature = new LinkedHashMap<>();
        declaredExecutorBeans().forEach(bean -> bySignature.putIfAbsent(bean.signature(), bean));
        return List.copyOf(bySignature.values());
    }

    /**
     * {@code @Async} occurrences in one Java source, with comments removed first so the prose about
     * this very rule — of which the configuration classes carry a good deal — is not counted.
     *
     * <p>It is the half of this class that can be asserted directly, which is what keeps the file
     * scan above from passing vacuously: a guard that only reads an already-clean application
     * cannot tell "nothing to find" from "found nothing".</p>
     */
    private static List<AsyncSite> parse(String file, String source) {
        Stripped stripped = strip(source);
        String code = stripped.code();
        // An annotation declaration is the one file whose @Async the classpath scan cannot reach —
        // an annotation type is an interface — so its sites are asserted but not compared.
        boolean declaresAnnotationType = ANNOTATION_DECLARATION.matcher(code).find();
        List<AsyncSite> sites = new ArrayList<>();
        Matcher matcher = ASYNC_ANNOTATION.matcher(code);
        while (matcher.find()) {
            if (stripped.insideLiteral()[matcher.start()]) {
                // Text in a log line or an exception message, not an annotation. The literals are
                // kept rather than blanked because the qualifier itself is one, so they have to be
                // skipped here instead.
                continue;
            }
            String argument = matcher.group(1);
            if (argument == null) {
                sites.add(new AsyncSite(file, file, null, true, !declaresAnnotationType));
                continue;
            }
            String value = argument.trim().replaceFirst("^value\\s*=\\s*", "").trim();
            Matcher literal = STRING_LITERAL.matcher(value);
            if (literal.matches()) {
                String name = literal.group(1);
                sites.add(new AsyncSite(file, file, name.isBlank() ? null : name, true,
                        !declaresAnnotationType));
            } else {
                // Named, but not by anything this scan can evaluate. scanAnnotations() resolves it.
                sites.add(new AsyncSite(file, file, value, false, !declaresAnnotationType));
            }
        }
        return sites;
    }

    private static final Pattern ANNOTATION_DECLARATION = Pattern.compile("@interface\\b");

    /**
     * Java source with its comments removed, and a flag per remaining character saying whether it
     * came from inside a literal.
     *
     * @param code          the source with comments removed and every literal copied verbatim
     * @param insideLiteral one entry per character of {@code code}
     */
    record Stripped(String code, boolean[] insideLiteral) {
    }

    /**
     * Java source with its comments removed and everything else — string, text-block and character
     * literals included — copied through verbatim.
     *
     * <p>Character by character rather than by regular expression, and that is the whole reason it
     * exists: a slash-star pair appears inside ordinary string literals (this application's security
     * matchers use ant patterns such as {@code "/api/v1/device/**"}), and a pattern that deletes
     * from a slash-star to the next star-slash swallows every line in between — over two thousand
     * characters of real code in one file here. A stripper that hides code is worse than none,
     * because this scan's whole claim is that it is total over the source.</p>
     *
     * <p>The literals are kept rather than blanked because the qualifier this class reads
     * <em>is</em> a string literal.</p>
     */
    /**
     * Java source with comments removed and everything else copied through verbatim.
     *
     * <p>Package-private so {@code BackgroundConnectionDemandTest} (#161) strips the same way. Its
     * scan looks for pools nobody declared, so a stripper that hides code is the worst failure
     * available there: silent, and towards passing.</p>
     *
     * @param source the file's text
     * @return the same text without its comments
     */
    static String stripComments(String source) {
        return strip(source).code();
    }

    /**
     * Comments removed, literals kept, and a record of which characters came from a literal.
     *
     * <p>Character by character rather than by regular expression, and that is the whole reason it
     * exists: a slash-star pair appears inside ordinary string literals (this application's security
     * matchers use ant patterns such as {@code "/api/v1/device/**"}), and a pattern that deletes
     * from a slash-star to the next star-slash swallows every line in between — over two thousand
     * characters of real code in one file here. A stripper that hides code is worse than none,
     * because this scan's whole claim is that it is total over the source.</p>
     *
     * <p>The literals are kept rather than blanked because the qualifier this class reads
     * <em>is</em> a string literal; the mask is how a match that begins inside one — an
     * {@code @Async} named in a log line or an exception message — is told apart from an annotation.
     * </p>
     *
     * <p>Package-private so {@code RawTimestampReadConventionTest} (#280) scans the same way. Its
     * own fixtures are Java sources held in string literals, so it needs the mask and not only the
     * stripped text: without it the guard would report its own test data as a violation.</p>
     */
    static Stripped strip(String source) {
        StringBuilder code = new StringBuilder(source.length());
        List<Boolean> literal = new ArrayList<>(source.length());
        int index = 0;
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            char next = index + 1 < length ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < length
                        && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    // Newlines are kept so a stripped comment cannot join two lines of code.
                    if (source.charAt(index) == '\n') {
                        append(code, literal, '\n', false);
                    }
                    index++;
                }
                index = Math.min(index + 2, length);
            } else if (current == '"' && next == '"' && index + 2 < length
                    && source.charAt(index + 2) == '"') {
                index = copyTextBlock(source, index, code, literal);
            } else if (current == '"' || current == '\'') {
                index = copyLiteral(source, index, current, code, literal);
            } else {
                append(code, literal, current, false);
                index++;
            }
        }
        boolean[] mask = new boolean[literal.size()];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = literal.get(i);
        }
        return new Stripped(code.toString(), mask);
    }

    private static void append(StringBuilder code, List<Boolean> literal, char c, boolean inLiteral) {
        code.append(c);
        literal.add(inLiteral);
    }

    /**
     * Copies a string or character literal verbatim, honouring escapes.
     *
     * <p>The opening and closing quotes are marked as code rather than as literal content, so an
     * {@code @Async("…")} qualifier is still read while the text between the quotes is not mistaken
     * for one.</p>
     *
     * @return the index after the literal
     */
    private static int copyLiteral(String source, int start, char quote, StringBuilder code,
                                   List<Boolean> literal) {
        int length = source.length();
        append(code, literal, quote, false);
        int index = start + 1;
        while (index < length) {
            char current = source.charAt(index);
            boolean closing = current == quote;
            append(code, literal, current, !closing);
            index++;
            if (current == '\\' && index < length) {
                append(code, literal, source.charAt(index), true);
                index++;
            } else if (closing || current == '\n') {
                // An unterminated literal cannot outlive its line, so a newline ends it too rather
                // than letting one typo desynchronize the rest of the file.
                break;
            }
        }
        return index;
    }

    /** Copies a text block verbatim; returns the index after its closing delimiter. */
    private static int copyTextBlock(String source, int start, StringBuilder code,
                                     List<Boolean> literal) {
        int length = source.length();
        for (int i = 0; i < 3; i++) {
            append(code, literal, '"', false);
        }
        int index = start + 3;
        while (index < length) {
            if (source.charAt(index) == '\\' && index + 1 < length) {
                append(code, literal, source.charAt(index), true);
                append(code, literal, source.charAt(index + 1), true);
                index += 2;
                continue;
            }
            if (source.charAt(index) == '"' && index + 2 < length
                    && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                for (int i = 0; i < 3; i++) {
                    append(code, literal, '"', false);
                }
                return index + 3;
            }
            append(code, literal, source.charAt(index), true);
            index++;
        }
        return index;
    }

    /**
     * {@code @Async}, with its argument list only when the parenthesis is the next thing written —
     * otherwise the parameter list of the annotated method would be read as the qualifier.
     */
    private static final Pattern ASYNC_ANNOTATION = Pattern.compile(
            "@(?:org\\.springframework\\.scheduling\\.annotation\\.)?Async\\b\\s*(?:\\(([^)]*)\\))?");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** The file a class is written in, with a nested class mapped to its outer class's file. */
    private static String sourceFileOf(Class<?> type) {
        String topLevel = type.getName().split("\\$", 2)[0];
        return topLevel.replace('.', '/') + ".java";
    }

    /**
     * Memoized: the assertions below read the inventory several times each, and every read is an
     * ASM scan of the classpath plus a {@code Class.forName} per candidate.
     */
    private static List<Class<?>> productionClasses() {
        if (productionClasses == null) {
            productionClasses = scanProductionClasses();
        }
        return productionClasses;
    }

    private static List<Class<?>> productionClasses;

    private static List<Class<?>> scanProductionClasses() {
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
