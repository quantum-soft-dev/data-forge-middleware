package com.bitbi.dfm.config;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every value a hypersistence-utils JSON attribute can hold is {@link Serializable} (issue #366).
 * <p>
 * hypersistence-utils 3.15 clones a JSON attribute that is neither a {@code String} nor a
 * {@code JsonNode} by Java serialization, and Hibernate clones it on every load (the dirty-checking
 * snapshot) and on every {@code em.remove} (the deleted state). A value type that is not
 * {@code Serializable} therefore fails at run time and only on the path that clones: #302 found it
 * for {@code TableChangeStats} on load, #366 for {@code BatchTableStats} on delete, where loading,
 * changing and flushing the same batch had worked and nothing in the suite deleted one. Neither the
 * compiler nor a test of the column's JSON shape can see it, so this reads the declared types.
 * </p>
 * <p>
 * The walk follows the generic type of each attribute: a {@code Map}, {@code Collection} or array is
 * a container and its arguments are walked; any other class must be concrete and
 * {@code Serializable}, and a class of this application is walked field by field, since Java
 * serialization needs the whole graph. {@code Object} is accepted: it is untyped JSON, which Jackson
 * binds to {@code String}, a number, {@code Boolean}, {@code LinkedHashMap} or {@code ArrayList}, all
 * serializable — the one gap, stated rather than closed, is code putting a non-serializable object
 * into such a map by hand. A type variable or wildcard is refused, because nothing about its runtime
 * value can be proven.
 * </p>
 */
@DisplayName("JSONB attributes hold only Serializable values (issue #366)")
class JsonbAttributeSerializableConventionTest {

    private static final String BASE_PACKAGE = "com.bitbi.dfm";
    private static final String JSON_TYPE_PACKAGE = "io.hypersistence.utils.hibernate.type.json";

    @Test
    @DisplayName("every JSON attribute of a persistent class in src/main is Serializable, contents included")
    void everyJsonAttributeValueIsSerializable() {
        List<Field> attributes = jsonAttributes();

        List<String> violations = new ArrayList<>();
        for (Field attribute : attributes) {
            for (String problem : nonSerializableParts(attribute.getGenericType())) {
                violations.add(attribute.getDeclaringClass().getSimpleName() + "." + attribute.getName()
                        + ": " + problem);
            }
        }

        assertThat(violations)
                .as("hypersistence-utils clones a JSON attribute by Java serialization on load and on "
                        + "em.remove; a value that is not Serializable fails there (#302, #366)")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the JSON attributes it exists for, so an empty result is not a pass")
    void scanFindsTheKnownJsonAttributes() {
        assertThat(jsonAttributes())
                .extracting(field -> field.getDeclaringClass().getSimpleName() + "." + field.getName())
                .contains("Batch.tableStats", "ChangelogSegment.stats", "SiteSchema.schemaData");
    }

    @Nested
    @DisplayName("the type walk")
    class Walk {

        record Plain(long count) {
        }

        record Stats(long count) implements Serializable {
        }

        record Wrapper(Plain inner) implements Serializable {
        }

        static class Holder<T> {
            Map<String, Stats> serializableValues;
            Map<String, Plain> plainValues;
            Map<String, Object> untyped;
            List<Plain> plainElements;
            Map<String, List<Stats>> nested;
            Map<String, Wrapper> wrapperWithPlainComponent;
            Stats[] array;
            Plain[] plainArray;
            TreeMap<String, Stats> concreteMap;
            Map<String, T> typeVariable;
            Map<String, ? extends Stats> wildcard;
            String text;
            CharSequence notConcrete;
        }

        private List<String> walk(String field) throws NoSuchFieldException {
            return nonSerializableParts(Holder.class.getDeclaredField(field).getGenericType());
        }

        @Test
        void acceptsSerializableContents() throws Exception {
            assertThat(walk("serializableValues")).isEmpty();
            assertThat(walk("untyped")).isEmpty();
            assertThat(walk("nested")).isEmpty();
            assertThat(walk("array")).isEmpty();
            assertThat(walk("concreteMap")).isEmpty();
            assertThat(walk("text")).isEmpty();
        }

        @Test
        void refusesANonSerializableMapValue() throws Exception {
            assertThat(walk("plainValues")).singleElement().asString().contains(Plain.class.getName());
        }

        @Test
        void refusesANonSerializableCollectionElementOrArrayComponent() throws Exception {
            assertThat(walk("plainElements")).singleElement().asString().contains(Plain.class.getName());
            assertThat(walk("plainArray")).singleElement().asString().contains(Plain.class.getName());
        }

        @Test
        void refusesANonSerializableComponentOfASerializableRecord() throws Exception {
            assertThat(walk("wrapperWithPlainComponent")).singleElement().asString()
                    .contains(Plain.class.getName());
        }

        @Test
        void refusesWhatItCannotProve() throws Exception {
            assertThat(walk("typeVariable")).singleElement().asString().contains("type variable");
            assertThat(walk("wildcard")).singleElement().asString().contains("wildcard");
            assertThat(walk("notConcrete")).singleElement().asString().contains("not concrete");
        }
    }

    static List<Field> jsonAttributes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        // An abstract @MappedSuperclass is still persistent state.
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));

        List<Field> attributes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName(), false,
                        JsonbAttributeSerializableConventionTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Cannot load scanned class " + definition.getBeanClassName(), e);
            }
            for (Field field : type.getDeclaredFields()) {
                Type annotation = field.getAnnotation(Type.class);
                if (annotation != null && annotation.value().getPackageName().startsWith(JSON_TYPE_PACKAGE)) {
                    attributes.add(field);
                }
            }
        }
        return attributes;
    }

    static List<String> nonSerializableParts(java.lang.reflect.Type type) {
        List<String> problems = new ArrayList<>();
        walk(type, problems, new HashSet<>());
        return problems;
    }

    private static void walk(java.lang.reflect.Type type, List<String> problems, Set<Class<?>> visited) {
        if (type instanceof ParameterizedType parameterized) {
            walkClass((Class<?>) parameterized.getRawType(), problems, visited);
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, problems, visited);
            }
        } else if (type instanceof GenericArrayType array) {
            walk(array.getGenericComponentType(), problems, visited);
        } else if (type instanceof Class<?> clazz) {
            walkClass(clazz, problems, visited);
        } else if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            problems.add("type variable " + variable.getName() + " cannot be proven Serializable");
        } else {
            problems.add("wildcard " + type.getTypeName() + " cannot be proven Serializable");
        }
    }

    private static void walkClass(Class<?> clazz, List<String> problems, Set<Class<?>> visited) {
        if (clazz.isPrimitive() || clazz == Object.class || !visited.add(clazz)) {
            return;
        }
        if (clazz.isArray()) {
            walk(clazz.getComponentType(), problems, visited);
            return;
        }
        if (clazz.isInterface() && (Map.class.isAssignableFrom(clazz) || Collection.class.isAssignableFrom(clazz))) {
            // A container; its contents are its type arguments, walked by the caller.
            return;
        }
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
            problems.add(clazz.getName() + " is not concrete, so its runtime value cannot be proven Serializable");
            return;
        }
        if (!Serializable.class.isAssignableFrom(clazz)) {
            problems.add(clazz.getName() + " is not Serializable");
            return;
        }
        if (clazz.getName().startsWith(BASE_PACKAGE)) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
                    walk(field.getGenericType(), problems, visited);
                }
            }
        }
    }
}
