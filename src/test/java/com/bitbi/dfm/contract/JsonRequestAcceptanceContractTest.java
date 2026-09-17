package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Characterization of how JSON request bodies are accepted, pinned on Spring Boot 3.5 / Jackson 2
 * before the move to Jackson 3 (issue #300, the safety net for #302).
 * <p>
 * Jackson 3 flips deserialization defaults a client can hit without changing a byte it sends:
 * {@code FAIL_ON_TRAILING_TOKENS} (content after the JSON value), {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * ({@code null} into an {@code int}/{@code boolean}), and — in the other direction — the unknown
 * property rule Spring Boot already relaxes. The statuses below are what the application answers
 * <em>today</em>, including two that are arguably wrong (an unreadable body and an unknown enum
 * value both reach the catch-all handler and answer 500): a characterization pins behaviour, it
 * does not endorse it, so a change on #302 is visible either way.
 * </p>
 * The main subject is {@code POST /api/v1/device/errors}, a Device API body with a free-form map
 * and an enum; the one primitive in a request body is pinned on its own route. The class is {@code @Transactional}, so the error logs it writes are rolled back.
 */
@Transactional
@DisplayName("#300 — JSON request acceptance (Boot 3.5 characterization)")
class JsonRequestAcceptanceContractTest extends BaseIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private int postErrorLog(String json) throws Exception {
        return mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG)
                        .header("Authorization", generateToken("store-01.example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("an unknown property is ignored: 201")
    void unknownPropertyIsIgnored() throws Exception {
        assertThat(postErrorLog("{\"type\":\"T\",\"message\":\"m\",\"notAField\":42}")).isEqualTo(201);
    }

    @Test
    @DisplayName("trailing content after the JSON value is ignored: 201")
    void trailingTokensAreIgnored() throws Exception {
        assertThat(postErrorLog("{\"type\":\"T\",\"message\":\"m\"} trailing")).isEqualTo(201);
        assertThat(postErrorLog("{\"type\":\"T\",\"message\":\"m\"}{\"type\":\"U\"}")).isEqualTo(201);
    }

    @Test
    @DisplayName("an explicit null for an optional enum reads as absent: 201")
    void nullEnumIsAbsent() throws Exception {
        assertThat(postErrorLog("{\"type\":\"T\",\"message\":\"m\",\"severity\":null}")).isEqualTo(201);
    }

    @Test
    @DisplayName("an unreadable body and an unknown enum value reach the catch-all: 500 with the generic body")
    void unreadableBodyAnswers500() throws Exception {
        for (String json : List.of("{\"type\":", "{\"type\":\"T\",\"message\":\"m\",\"severity\":\"warning\"}")) {
            var response = mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG)
                            .header("Authorization", generateToken("store-01.example.com"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as(json).isEqualTo(500);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":500,\"error\":\"Internal Server Error\","
                    + "\"message\":\"An unexpected error occurred\",\"path\":\"/api/v1/device/errors\"}",
                    response.getContentAsString());
        }
    }

    /**
     * The inventory of primitives in {@code @RequestBody} types: these are the fields where Jackson 3's
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} turns an accepted {@code null} into an unreadable body. The
     * scan walks every handler method Spring MVC registered, and every record component or field of
     * a body type that belongs to this application, recursively (enums excluded; a primitive array
     * counts, since {@code null} elements in it fail the same way). A new primitive fails
     * here on purpose: whoever adds it pins what {@code null} for it answers, as
     * {@link #nullForAPrimitiveBooleanReadsAsFalse()} does for the one there is (one DTO, two routes).
     */
    @Test
    @DisplayName("the only primitive in a request body is ManualSqlGenerationRequestDto.forceFullGeneration (owner and admin routes)")
    void requestBodyPrimitiveInventory() {
        List<String> primitives = new ArrayList<>();
        int bodies = 0;
        for (HandlerMethod method : handlerMapping.getHandlerMethods().values()) {
            for (MethodParameter parameter : method.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    bodies++;
                    collectPrimitives(parameter.getGenericParameterType(),
                            method.getBeanType().getSimpleName() + "#" + method.getMethod().getName(),
                            new HashSet<>(), primitives);
                }
            }
        }

        assertThat(bodies).as("request bodies found by the scan").isGreaterThan(10);
        assertThat(primitives).containsExactlyInAnyOrder(
                "AccountPluginsController#generateSql.forceFullGeneration : boolean",
                "PluginAdminController#generateSqlForBatch.forceFullGeneration : boolean");
    }

    /**
     * {@code null} for the primitive {@code forceFullGeneration} is read as {@code false}: the body is
     * accepted and the request reaches the handler, which answers 404 because the seeded account has
     * no bit-bi activation. An unreadable body on the same route answers 500 (the catch-all), so 404
     * here proves deserialization succeeded.
     */
    @Test
    @DisplayName("null for a primitive boolean reads as false and the handler runs")
    void nullForAPrimitiveBooleanReadsAsFalse() throws Exception {
        String route = "/api/v1/account/plugins/bit-bi/generate-sql";
        String batch = "b1c2d3e4-f5a6-7890-bcde-f12345678903";
        for (String json : List.of("{\"batchId\":\"" + batch + "\",\"forceFullGeneration\":null}",
                "{\"batchId\":\"" + batch + "\"}")) {
            int status = mockMvc.perform(post(route)
                            .header("Authorization", "Bearer mock.user.jwt.token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(json).isEqualTo(404);
        }
        int unreadable = mockMvc.perform(post(route)
                        .header("Authorization", "Bearer mock.user.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":"))
                .andReturn().getResponse().getStatus();
        assertThat(unreadable).isEqualTo(500);
    }

    record ScanProbeNested(long count, String name) {
    }

    enum ScanProbeKind { A }

    record ScanProbe(int[] ids, Integer boxed, ScanProbeKind kind, List<ScanProbeNested> nested) {
    }

    /**
     * The scan itself: a primitive array, a primitive inside a nested record reached through a
     * {@code List}, and nothing for a boxed number or an enum — so the inventory above cannot pass
     * because the walk has gone blind.
     */
    @Test
    @DisplayName("the scan finds primitive arrays and primitives in nested records, not boxed values or enums")
    void scanSeesArraysAndNestedRecords() {
        List<String> found = new ArrayList<>();
        collectPrimitives(ScanProbe.class, "probe", new HashSet<>(), found);
        assertThat(found).containsExactlyInAnyOrder("probe.ids : int[]", "probe.nested.count : long");
    }

    static void collectPrimitives(Type type, String path, Set<Class<?>> seen, List<String> out) {
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectPrimitives(argument, path, seen, out);
            }
            collectPrimitives(parameterized.getRawType(), path, seen, out);
            return;
        }
        if (!(type instanceof Class<?> clazz) || clazz.isEnum() || !clazz.getName().startsWith("com.bitbi.")
                || !seen.add(clazz)) {
            return;
        }
        if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                check(component.getType(), component.getGenericType(), path + "." + component.getName(), seen, out);
            }
            return;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    check(field.getType(), field.getGenericType(), path + "." + field.getName(), seen, out);
                }
            }
        }
    }

    private static void check(Class<?> raw, Type generic, String path, Set<Class<?>> seen, List<String> out) {
        Class<?> element = raw;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        if (element.isPrimitive()) {
            out.add(path + " : " + raw.getTypeName());
        } else {
            collectPrimitives(generic, path, seen, out);
        }
    }
}
