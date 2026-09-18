package com.bitbi.dfm.shared.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.util.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc contract tests for {@link GlobalExceptionHandler} mappings that must not
 * fall through to the generic 500 handler.
 * <p>
 * Uses a standalone stub controller so the contract (HTTP status + ErrorResponseDto body)
 * is verified through the real Spring MVC exception-resolution path without requiring
 * a full application context or Testcontainers.
 * </p>
 */
@DisplayName("GlobalExceptionHandler Contract Tests")
class GlobalExceptionHandlerContractTest {

    enum Grade { LOW, HIGH }

    record Item(String name, Grade grade) {
    }

    record Body(String type, Grade grade, List<Item> items) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/test/body")
        public String body(@RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/test/unauthorized")
        public String unauthorized() {
            throw new AuthorizationHelper.UnauthorizedException("Not authenticated");
        }

        @GetMapping("/test/data-integrity")
        public String dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement; duplicate key value violates unique constraint \"uq_sites_domain\"");
        }

        @GetMapping("/test/response-status")
        public String responseStatus() {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("UnauthorizedException returns 401 with standard ErrorResponseDto")
    void shouldMapUnauthorizedExceptionTo401() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Not authenticated"))
                .andExpect(jsonPath("$.path").value("/test/unauthorized"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns 409 with standard ErrorResponseDto")
    void shouldMapDataIntegrityViolationTo409() throws Exception {
        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(
                        "The request conflicts with existing data (duplicate or referenced records)."))
                .andExpect(jsonPath("$.path").value("/test/data-integrity"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("DataIntegrityViolationException response does not leak database details")
    void shouldNotLeakDatabaseDetailsOnDataIntegrityViolation() throws Exception {
        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(not(containsString("uq_sites_domain"))))
                .andExpect(jsonPath("$.message").value(not(containsString("duplicate key"))));
    }

    @Test
    @DisplayName("ResponseStatusException passes through with its declared status and reason, not 500")
    void shouldPassResponseStatusExceptionThroughWithDeclaredStatus() throws Exception {
        mockMvc.perform(get("/test/response-status"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value("/test/response-status"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * An unreadable body — broken JSON, an unknown enum value, a missing body, the wrong JSON shape —
     * is the client's error: 400 with the standard body, never the catch-all 500 (issue #320).
     */
    @Test
    @DisplayName("an unreadable request body returns 400 with standard ErrorResponseDto, not 500")
    void shouldMapUnreadableBodyTo400() throws Exception {
        for (String json : List.of("{\"type\":", "{\"type\":\"T\",\"grade\":\"warning\"}", "", "[1,2]")) {
            mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(containsString("Malformed request body")))
                    .andExpect(jsonPath("$.path").value("/test/body"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    @DisplayName("an unreadable body is answered without the parser's message, Java type names or the rejected value")
    void shouldNotLeakParserInternalsOnUnreadableBody() throws Exception {
        for (String json : List.of("{\"type\":", "{\"type\":\"T\",\"grade\":\"warning\"}", "[1,2]")) {
            mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(not(containsString("JSON parse error"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("jackson"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("com.bitbi"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("Grade"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("line:"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("warning"))));
        }
    }

    /**
     * The JSON path of the offending value is the client's own vocabulary — the field names it sent —
     * so it is named; the Java type behind it is not.
     */
    @Test
    @DisplayName("a value that does not bind names its JSON path, nested and indexed")
    void shouldNameTheJsonPathOfAValueThatDoesNotBind() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"T\",\"grade\":\"warning\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body at 'grade'"));
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"T\",\"items\":[{\"name\":\"a\"},{\"name\":\"b\",\"grade\":\"MID\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body at 'items[1].grade'"));
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    /**
     * A client's malformation is not a server fault: one WARN line naming the route, no ERROR and no
     * stack trace — an alert on ERROR must not fire for it.
     */
    @Test
    @DisplayName("an unreadable body logs one WARN without a stack trace, and no ERROR")
    void shouldLogUnreadableBodyAtWarnWithoutStackTrace() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class)) {
            mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"type\":"))
                    .andExpect(status().isBadRequest());

            assertThat(capture.eventsAt(Level.ERROR)).isEmpty();
            List<ILoggingEvent> warns = capture.eventsAt(Level.WARN);
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).getThrowableProxy()).isNull();
            assertThat(warns.get(0).getFormattedMessage()).contains("/test/body");
        }
    }
}
