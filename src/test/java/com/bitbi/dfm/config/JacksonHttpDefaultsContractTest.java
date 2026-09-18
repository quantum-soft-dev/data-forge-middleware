package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision record of issue #303: which Jackson 3 defaults the HTTP API accepted and which two it
 * pinned back to their Jackson 2 values.
 * <p>
 * Boot 4 builds the HTTP mapper with Jackson 3, whose defaults differ from Jackson 2's. #302 deferred
 * that difference wholesale with {@code spring.jackson.use-jackson2-defaults: true}; #303 removed the
 * flag and took the eighteen differences one at a time. Five of them never reached the HTTP mapper —
 * Boot pins dates, durations, unknown properties and both fast number parsers itself — and of the
 * thirteen that did, eleven were measured to change nothing a client can observe and were accepted,
 * while two change a request that works today into a 500 and were pinned.
 * <p>
 * This test is the record of all eighteen, so a later Boot or Jackson release that flips one of them
 * fails here instead of on a client — the five Boot pins itself included, since "Boot pins it" is an
 * observation about this Boot version rather than a guarantee. It asserts four things:
 * <ol>
 *   <li>the compatibility flag is gone and stays gone — a re-added flag would silently restore all
 *       eighteen Jackson 2 defaults and make the eleven accepted decisions untrue again;</li>
 *   <li>the mapper Boot builds from this application's own {@code spring.jackson.*} configuration
 *       carries exactly the decided value of every feature, pinned and accepted alike;</li>
 *   <li>no enum overrides {@code toString()} — the one fact the two accepted enum decisions rest on,
 *       and the one that would otherwise break silently;</li>
 *   <li>every default is still <em>in</em> the table — one that differs between the two modes must
 *       carry a verdict, and the five Boot pins itself are named in {@link #BOOT_PINNED}, since a
 *       difference-based check is by construction blind to a row that never differs.</li>
 * </ol>
 * The behavioural half of the two pinned decisions lives in {@code JsonRequestAcceptanceContractTest}
 * (#300), which drives real requests through MockMvc; this class holds the configuration that makes
 * those requests answer as they do.
 *
 * @see JsonMapperDecision
 */
class JacksonHttpDefaultsContractTest {

    /**
     * A Jackson feature whose default differs between Jackson 2 and Jackson 3 on the HTTP mapper, with
     * the value #303 decided for it and why.
     *
     * @param feature  the feature constant
     * @param enabled  the value the HTTP mapper must carry
     * @param decision {@code PINNED} to keep the Jackson 2 behaviour, {@code ACCEPTED} to take
     *                 Jackson 3's, {@code UNCHANGED} for a default Boot pins itself either way
     * @param because  the evidence the decision rests on
     */
    record JsonMapperDecision(Object feature, boolean enabled, String decision, String because) {
    }

    /**
     * Every Jackson default this application had to decide, with its verdict.
     * <p>
     * The first is pinned because accepting it refuses a body that shipped clients send and are
     * answered 404/200 for today. The second was pinned by #303 for as long as an unreadable body
     * answered 500, and accepted by #320 once it answers 400: content after the JSON document — a
     * second concatenated object included, which used to be dropped silently — is now refused. The
     * other eleven accepted ones were each measured to be invisible on this surface. The last five never
     * differed on the HTTP mapper at all, because Boot pins them regardless of the flag; they are
     * asserted so that a Boot release which stops pinning them is caught here rather than by a client
     * reading a date as a number, and they are named in {@link #BOOT_PINNED} so that a verdict cannot
     * be deleted without a test noticing.
     */
    private static final List<JsonMapperDecision> DECISIONS = List.of(
            new JsonMapperDecision(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false, "PINNED",
                    "ManualSqlGenerationRequestDto.forceFullGeneration is the only primitive in any "
                            + "@RequestBody; an explicit null reads as false today and would become an "
                            + "unreadable body, so the owner and admin generate-SQL routes would refuse "
                            + "(400 since #320) a request they accept now"),
            new JsonMapperDecision(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true, "ACCEPTED",
                    "content after the JSON document is refused with 400 (#320) instead of being "
                            + "ignored; no serializer emits it, and a second concatenated object was "
                            + "dropped silently"),
            new JsonMapperDecision(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true, "ACCEPTED",
                    "records keep their creator order, so every DTO on this surface is unmoved; the two "
                            + "raw Page<> responses are reordered, and no JSON parser reads members by "
                            + "position"),
            new JsonMapperDecision(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, false, "ACCEPTED",
                    "all 22 @RequestBody types are records, bound through the canonical constructor"),
            new JsonMapperDecision(MapperFeature.USE_GETTERS_AS_SETTERS, false, "ACCEPTED",
                    "no request body is a class with a collection getter and no setter"),
            new JsonMapperDecision(MapperFeature.DETECT_PARAMETER_NAMES, true, "ACCEPTED",
                    "record components carry their own names, so implicit creator names change nothing"),
            new JsonMapperDecision(MapperFeature.FIX_FIELD_NAME_UPPER_CASE_PREFIX, true, "ACCEPTED",
                    "measured to leave record component names alone, s3Path and URL included"),
            new JsonMapperDecision(EnumFeature.WRITE_ENUMS_USING_TO_STRING, true, "ACCEPTED",
                    "no enum overrides toString(), so toString() is name() — held by "
                            + "noEnumOverridesToString()"),
            new JsonMapperDecision(EnumFeature.READ_ENUMS_USING_TO_STRING, true, "ACCEPTED",
                    "the request side of the same fact: UserRole and ErrorSeverity still read by name"),
            new JsonMapperDecision(DateTimeFeature.ONE_BASED_MONTHS, true, "ACCEPTED",
                    "no Month, YearMonth or MonthDay appears in any request or response body"),
            new JsonMapperDecision(DateTimeFeature.WRITE_UTC_AS_OFFSET, false, "ACCEPTED",
                    "no ZonedDateTime or OffsetDateTime appears in any body, and Instant renders with a "
                            + "Z either way"),
            new JsonMapperDecision(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false, "ACCEPTED",
                    "no BigDecimal and no JsonNode on the surface; numbers in a free-form "
                            + "Map<String, Object> bind as Double, so 2.50 echoes as 2.5 either way"),
            new JsonMapperDecision(SerializationFeature.FAIL_ON_EMPTY_BEANS, false, "ACCEPTED",
                    "no property-less type is serialized; the flip can only turn a 500 into {}"),
            new JsonMapperDecision(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false, "UNCHANGED",
                    "Boot disables it with or without the flag, which is why every #300 date assertion "
                            + "stayed green"),
            new JsonMapperDecision(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false, "UNCHANGED",
                    "Boot disables it with or without the flag"),
            new JsonMapperDecision(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false, "UNCHANGED",
                    "Boot disables it with or without the flag, so an unknown property is still ignored"),
            new JsonMapperDecision(StreamReadFeature.USE_FAST_DOUBLE_PARSER, true, "UNCHANGED",
                    "Boot enables it with or without the flag; it is a parsing-speed choice, but one that "
                            + "has had precision bugs of its own, so it is tracked rather than assumed"),
            new JsonMapperDecision(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER, true, "UNCHANGED",
                    "Boot enables it with or without the flag, and it decides how a very long number "
                            + "literal in a free-form Map<String, Object> is parsed"));

    /**
     * The five defaults Boot pins to the same value in both modes, named rather than derived.
     * <p>
     * These cannot be discovered by diffing the two mappers — that is what "Boot pins it" means — so
     * without this list a verdict for one of them could be deleted with nothing failing, which is
     * exactly the gap review round 2 found in the diff-based completeness check below. They are the
     * contract-relevant ones: dates and durations as ISO strings rather than numbers, an unknown
     * property ignored rather than refused, and the two number parsers.
     */
    private static final List<String> BOOT_PINNED = List.of(
            "DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS",
            "DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS",
            "DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES",
            "StreamReadFeature.USE_FAST_DOUBLE_PARSER",
            "StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER");

    /** The verdicts #303 took: thirteen that differ between the two modes plus {@link #BOOT_PINNED}. */
    private static final int EXPECTED_VERDICTS = 13 + 5;

    /**
     * Every Jackson feature namespace the HTTP mapper carries, so the completeness check sees a default
     * added to any of them rather than only to the ones #303 happened to touch.
     */
    private static final Class<?>[] FEATURE_ENUMS = {
            DeserializationFeature.class,
            SerializationFeature.class,
            MapperFeature.class,
            EnumFeature.class,
            DateTimeFeature.class,
            JsonNodeFeature.class,
            StreamReadFeature.class,
            tools.jackson.core.StreamWriteFeature.class
    };

    /**
     * The compatibility flag, in every spelling a configuration file or an environment override could
     * use. Relaxed binding means {@code SPRING_JACKSON_USE_JACKSON2_DEFAULTS} reaches the same property.
     */
    private static final Pattern USE_JACKSON2_DEFAULTS =
            Pattern.compile("use[-_]?jackson2[-_]?defaults", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("the Jackson 2 compatibility flag is gone from every configuration file")
    void theCompatibilityFlagIsNotConfigured() {
        List<String> offenders = new ArrayList<>();
        for (Path file : configurationFiles()) {
            if (USE_JACKSON2_DEFAULTS.matcher(withoutComments(read(file))).find()) {
                offenders.add(relative(file));
            }
        }
        assertThat(offenders)
                .withFailMessage(() -> "spring.jackson.use-jackson2-defaults is set in " + offenders
                        + ". It restores all eighteen Jackson 2 defaults at once, which silently undoes "
                        + "every decision recorded in this class — including the two that are pinned "
                        + "here deliberately. Remove it, or change the decisions deliberately (#303).")
                .isEmpty();
    }

    @Test
    @DisplayName("the HTTP mapper carries the decided value of every Jackson default")
    void theHttpMapperCarriesTheDecidedDefaults() {
        ObjectMapper mapper = httpMapper();

        List<String> wrong = new ArrayList<>();
        for (JsonMapperDecision decision : DECISIONS) {
            boolean actual = isEnabled(mapper, decision.feature());
            if (actual != decision.enabled()) {
                wrong.add("%s.%s is %s but #303 %s it as %s — %s".formatted(
                        decision.feature().getClass().getSimpleName(), decision.feature(), actual,
                        decision.decision().toLowerCase(), decision.enabled(), decision.because()));
            }
        }
        assertThat(wrong)
                .withFailMessage(() -> "The HTTP mapper no longer matches the decisions of #303:\n  "
                        + String.join("\n  ", wrong)
                        + "\nEach of these is a change to what clients read or what the API accepts. "
                        + "Either restore the value through spring.jackson.*, or take the new one "
                        + "deliberately and update this table with the evidence.")
                .isEmpty();
    }

    @Test
    @DisplayName("every default that actually differs between the two modes has a verdict")
    void theDecisionTableCoversEveryDifference() {
        ObjectMapper jackson2 = mapperWith("spring.jackson.use-jackson2-defaults=true");
        ObjectMapper jackson3 = mapperWith("spring.jackson.use-jackson2-defaults=false");

        List<String> recorded = DECISIONS.stream()
                .map(d -> d.feature().getClass().getSimpleName() + "." + d.feature())
                .toList();
        assertThat(recorded).as("a feature recorded twice would hide one of its two verdicts")
                .doesNotHaveDuplicates();

        // The diff below cannot see these: they hold the same value in both modes by definition, so
        // deleting one of their verdicts would fail nothing (review round 2). Named, therefore.
        assertThat(recorded)
                .withFailMessage(() -> "These defaults are pinned by Boot rather than by this "
                        + "application, and their verdicts have gone missing from DECISIONS: "
                        + BOOT_PINNED.stream().filter(f -> !recorded.contains(f)).toList()
                        + ". They hold the same value in both modes, so the difference-based check "
                        + "below is blind to their removal — that is why they are named here. A Boot "
                        + "release that stops pinning one of them changes what clients read (a date as "
                        + "a number, a rejected unknown property), and this class claims to be the "
                        + "record of all " + EXPECTED_VERDICTS + ".")
                .containsAll(BOOT_PINNED);
        assertThat(recorded).as("the verdict count #303 settled on, pinned so a row cannot go missing")
                .hasSize(EXPECTED_VERDICTS);

        List<String> undecided = new ArrayList<>();
        for (Class<?> featureEnum : FEATURE_ENUMS) {
            for (Object feature : featureEnum.getEnumConstants()) {
                if (isEnabled(jackson2, feature) == isEnabled(jackson3, feature)) {
                    continue;
                }
                String name = featureEnum.getSimpleName() + "." + feature;
                if (!recorded.contains(name)) {
                    undecided.add(name);
                }
            }
        }
        assertThat(undecided)
                .withFailMessage(() -> "These Jackson defaults differ between the Jackson 2 compatibility "
                        + "mode and Jackson 3, and #303 has no verdict for them:\n  "
                        + String.join("\n  ", undecided)
                        + "\nThat is how this table silently stops describing the HTTP API: a Jackson or "
                        + "Boot upgrade adds a default, nobody notices, and the change reaches a client. "
                        + "Decide each one — accept it with the evidence, or pin it in application.yml — "
                        + "and add it to DECISIONS.")
                .isEmpty();
    }

    @Test
    @DisplayName("no enum overrides toString(), which is what makes the enum decisions invisible")
    void noEnumOverridesToString() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : applicationClasses()) {
            if (!type.isEnum()) {
                continue;
            }
            try {
                type.getDeclaredMethod("toString");
                offenders.add(type.getName());
            } catch (NoSuchMethodException expected) {
                // Enum.toString() returns name(), which is what both accepted enum decisions rest on.
            }
        }
        assertThat(offenders)
                .withFailMessage(() -> "These enums override toString(): " + offenders
                        + ". Since #303 the HTTP mapper writes and reads enums by toString() rather than "
                        + "by name(), so an override silently changes the wire value of every DTO "
                        + "carrying the enum — and, on a request body, rejects the value clients send "
                        + "today. AdminActionType, ActionStatus and UserRole already hold a label that "
                        + "differs from name(); they are safe only because none of them returns it from "
                        + "toString(). Use @JsonValue if a different wire value is actually wanted, and "
                        + "treat it as an API change.")
                .isEmpty();
    }

    @Test
    @DisplayName("the enum scan reaches the enums it is meant to guard")
    void theEnumScanIsNotBlind() {
        List<Class<?>> enums = applicationClasses().stream().filter(Class::isEnum).toList();

        assertThat(enums)
                .withFailMessage("The classpath scan found no enums at all — it has gone blind, and "
                        + "noEnumOverridesToString() would pass against anything.")
                .hasSizeGreaterThan(10);
        assertThat(enums.stream().map(Class::getSimpleName))
                .as("the enums that reach a request or a response body")
                .contains("UserRole", "ErrorSeverity", "SiteType", "AdminActionType", "ActionStatus");
    }

    @Test
    @DisplayName("the configuration scan reads configuration and not prose")
    void theConfigurationScanIgnoresComments() {
        assertThat(USE_JACKSON2_DEFAULTS.matcher(withoutComments("""
                spring:
                  jackson:
                    # No use-jackson2-defaults here, deliberately (#303).
                    deserialization:
                      fail-on-trailing-tokens: false
                """)).find())
                .withFailMessage("The comment recording why the flag is absent must not itself be read "
                        + "as the flag — otherwise documenting the decision fails the test that holds it.")
                .isFalse();

        assertThat(USE_JACKSON2_DEFAULTS.matcher(withoutComments("""
                spring:
                  jackson:
                    use-jackson2-defaults: true
                """)).find())
                .withFailMessage("The scan must still see the flag when it is really set.")
                .isTrue();
        assertThat(USE_JACKSON2_DEFAULTS.matcher(withoutComments(
                "SPRING_JACKSON_USE_JACKSON2_DEFAULTS=true")).find())
                .withFailMessage("Relaxed binding means the environment-variable spelling sets the same "
                        + "property, so the scan must see it too.")
                .isTrue();
    }

    /**
     * The mapper Boot builds from this application's own {@code spring.jackson.*} configuration.
     * <p>
     * The properties are read out of {@code application.yml} rather than written into the test, so the
     * assertions are about the file that ships and not about a copy of it that could drift.
     */
    private ObjectMapper httpMapper() {
        Map<String, Object> jackson = applicationJacksonProperties();
        return mapperWith(jackson.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new));
    }

    /** The mapper Boot's Jackson auto-configuration builds under {@code properties}. */
    private ObjectMapper mapperWith(String... properties) {
        ObjectMapper[] holder = new ObjectMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfiguration.class)
                .withPropertyValues(properties)
                .run(context -> holder[0] = context.getBean(ObjectMapper.class));

        assertThat(holder[0])
                .withFailMessage("Boot's Jackson auto-configuration produced no ObjectMapper — the "
                        + "probe cannot say anything about the HTTP mapper.")
                .isNotNull();
        return holder[0];
    }

    private static Map<String, Object> applicationJacksonProperties() {
        Map<String, Object> jackson = new LinkedHashMap<>();
        ScheduledTaskInventoryTest.optionalYaml("application.yml")
                .forEach((key, value) -> {
                    if (key.startsWith("spring.jackson.")) {
                        jackson.put(key, value);
                    }
                });
        return jackson;
    }

    private static boolean isEnabled(ObjectMapper mapper, Object feature) {
        if (feature instanceof DeserializationFeature f) {
            return mapper.deserializationConfig().isEnabled(f);
        }
        if (feature instanceof SerializationFeature f) {
            return mapper.serializationConfig().isEnabled(f);
        }
        if (feature instanceof MapperFeature f) {
            return mapper.serializationConfig().isEnabled(f);
        }
        if (feature instanceof EnumFeature f) {
            return mapper.serializationConfig().isEnabled(f);
        }
        if (feature instanceof DateTimeFeature f) {
            return mapper.serializationConfig().isEnabled(f);
        }
        if (feature instanceof JsonNodeFeature f) {
            return mapper.serializationConfig().isEnabled(f);
        }
        if (feature instanceof StreamReadFeature f) {
            return mapper.tokenStreamFactory().isEnabled(f);
        }
        if (feature instanceof tools.jackson.core.StreamWriteFeature f) {
            return mapper.tokenStreamFactory().isEnabled(f);
        }
        throw new IllegalArgumentException("Unhandled feature type: " + feature.getClass()
                + ". It is listed in FEATURE_ENUMS, so the completeness check cannot read it — add a "
                + "branch here rather than dropping it from the list, which would make the check blind "
                + "to that whole namespace.");
    }

    private static List<Class<?>> applicationClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.bitbi.dfm")) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                types.add(Class.forName(className, false,
                        JacksonHttpDefaultsContractTest.class.getClassLoader()));
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                // A class the scan can name but not load cannot declare a toString() we could read.
            }
        }
        return types;
    }

    private static List<Path> configurationFiles() {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/test/resources", "k8s")) {
            Path dir = RunOwnedScratch.projectRoot().resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (String suffix : List.of(".yml", ".yaml", ".properties")) {
                files.addAll(walk(dir, suffix));
            }
        }
        assertThat(files)
                .withFailMessage("No configuration files found — the scan has gone blind")
                .isNotEmpty();
        return files;
    }

    private static List<Path> walk(Path dir, String suffix) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(suffix)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + dir, e);
        }
    }

    private static String withoutComments(String configuration) {
        StringBuilder kept = new StringBuilder();
        for (String line : configuration.split("\n", -1)) {
            kept.append(line.stripLeading().startsWith("#") ? "" : line).append('\n');
        }
        return kept.toString();
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
