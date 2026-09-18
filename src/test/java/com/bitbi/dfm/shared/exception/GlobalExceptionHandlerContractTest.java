package com.bitbi.dfm.shared.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.util.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    record Named(@NotBlank String name) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/test/body")
        public String body(@RequestBody Body body) {
            return "ok";
        }

        @PostMapping("/test/named")
        public String named(@Valid @RequestBody Named named) {
            return "ok";
        }

        /** The shape of {@code /sql-changes}: a route that produces only text. */
        @GetMapping(value = "/test/text", produces = MediaType.TEXT_PLAIN_VALUE)
        public String text() {
            return "ok";
        }

        @GetMapping("/test/json")
        public Body json() {
            return new Body("T", Grade.LOW, List.of());
        }

        @GetMapping("/test/missing-header")
        public String missingHeader() throws Exception {
            throw new MissingRequestHeaderException("X-Plugin-Api-Key",
                    new MethodParameter(ThrowingController.class.getMethod("missingHeader"), -1));
        }

        @GetMapping("/test/async-timeout")
        public String asyncTimeout() {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/test/error-response")
        public String errorResponse() {
            throw new ErrorResponseException(HttpStatus.PAYLOAD_TOO_LARGE,
                    new IllegalStateException("com.bitbi.dfm.internal.Secret leaked"));
        }

        @GetMapping("/test/no-resource")
        public String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource", "internal/static/path");
        }

        @GetMapping("/test/method-not-supported")
        public String methodNotSupported() throws HttpRequestMethodNotSupportedException {
            throw new HttpRequestMethodNotSupportedException("PATCH", List.of("GET"));
        }

        @GetMapping("/test/response-status-503")
        public String responseStatus503() {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Paused for maintenance");
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

    // ---------------------------------------------------------------------------------------------
    // Issue #336: Spring MVC's own client-side exceptions answer their own status, not the catch-all
    // 500. The two reachable today are driven by a real request; the rest through the ErrorResponse
    // path, with an exception thrown from the stub controller.
    // ---------------------------------------------------------------------------------------------

    /**
     * A JSON route handed a body in a media type it does not read: 415 with the standard body, and the
     * {@code Accept} header naming what the route does read — driven by a real {@code Content-Type}.
     */
    @Test
    @DisplayName("an unsupported Content-Type returns 415 with standard ErrorResponseDto, not 500")
    void shouldMapUnsupportedContentTypeTo415() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("type=T"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.message").value(containsString("text/plain")))
                .andExpect(jsonPath("$.path").value("/test/body"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
    }

    /**
     * A route that produces only text, asked for JSON — the shape of {@code /sql-changes}: 406, and the
     * error body is still the standard one, because JSON is what the client accepts.
     */
    @Test
    @DisplayName("an Accept the route cannot produce returns 406 with standard ErrorResponseDto, not 500")
    void shouldMapNotAcceptableTo406() throws Exception {
        mockMvc.perform(get("/test/text").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.error").value("Not Acceptable"))
                .andExpect(jsonPath("$.message").value(containsString("text/plain")))
                .andExpect(jsonPath("$.path").value("/test/text"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * The other 406: a JSON route asked for text only. No representation of the error body is
     * acceptable either, so the status is all the client can be given — 406 with an empty body, never
     * 500, never an ERROR, and no "Failure in @ExceptionHandler" stack trace from Spring's resolver,
     * which is what writing the JSON body into a text-only response would produce.
     */
    @Test
    @DisplayName("a JSON route asked for text only returns a body-less 406, with no ERROR and no resolver failure")
    void shouldAnswer406WhenNoRepresentationOfTheErrorIsAcceptable() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class);
             LogCapture resolver = LogCapture.attachTo(ExceptionHandlerExceptionResolver.class)) {
            mockMvc.perform(get("/test/json").accept(MediaType.TEXT_PLAIN))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(content().string(""));

            assertThat(capture.eventsAt(Level.ERROR)).isEmpty();
            assertThat(capture.eventsAt(Level.WARN)).singleElement()
                    .satisfies(warn -> assertThat(warn.getThrowableProxy()).isNull());
            assertThat(resolver.eventsAt(Level.WARN)).isEmpty();
            assertThat(resolver.eventsAt(Level.ERROR)).isEmpty();
        }
    }

    /** An {@code Accept} header that is not even a media type is the same case: 406, no body, no resolver failure. */
    @Test
    @DisplayName("an unparseable Accept header returns a body-less 406, with no resolver failure")
    void shouldAnswer406ForAnUnparseableAcceptHeader() throws Exception {
        try (LogCapture resolver = LogCapture.attachTo(ExceptionHandlerExceptionResolver.class)) {
            mockMvc.perform(get("/test/json").header(HttpHeaders.ACCEPT, "not a media type"))
                    .andExpect(status().isNotAcceptable());

            assertThat(resolver.eventsAt(Level.WARN)).isEmpty();
        }
    }

    @Test
    @DisplayName("415 and 406 each log one WARN without a stack trace, and no ERROR")
    void shouldLogMediaTypeFailuresAtWarnWithoutStackTrace() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class)) {
            mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                    .andExpect(status().isUnsupportedMediaType());
            mockMvc.perform(get("/test/text").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotAcceptable());

            assertThat(capture.eventsAt(Level.ERROR)).isEmpty();
            List<ILoggingEvent> warns = capture.eventsAt(Level.WARN);
            assertThat(warns).hasSize(2);
            assertThat(warns).allSatisfy(warn -> assertThat(warn.getThrowableProxy()).isNull());
            assertThat(warns.get(0).getFormattedMessage()).contains("415").contains("/test/body");
            assertThat(warns.get(1).getFormattedMessage()).contains("406").contains("/test/text");
        }
    }

    @Test
    @DisplayName("415 and 406 messages name no Java class and echo no request header but the media type")
    void shouldNotLeakInternalsOnMediaTypeFailures() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("x")
                        .header("X-Plugin-Api-Key", "plk_secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))))
                .andExpect(jsonPath("$.message").value(not(containsString("org.springframework"))))
                .andExpect(jsonPath("$.message").value(not(containsString("plk_secret"))));
        mockMvc.perform(get("/test/text").accept(MediaType.APPLICATION_JSON)
                        .header("X-Plugin-Api-Key", "plk_secret"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))))
                .andExpect(jsonPath("$.message").value(not(containsString("org.springframework"))))
                .andExpect(jsonPath("$.message").value(not(containsString("plk_secret"))));
    }

    /**
     * The types unreachable today (no {@code @RequestHeader}, no multipart route, no async MVC) are
     * closed by the same path: any {@link org.springframework.web.ErrorResponse} answers its own 4xx
     * status with its client-facing detail, at WARN.
     */
    @Test
    @DisplayName("any client-side ErrorResponse answers its own status at WARN, e.g. a missing header → 400")
    void shouldAnswerAClientSideErrorResponseWithItsOwnStatus() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/test/missing-header"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(containsString("X-Plugin-Api-Key")))
                    .andExpect(jsonPath("$.path").value("/test/missing-header"))
                    .andExpect(jsonPath("$.timestamp").exists());

            assertThat(capture.eventsAt(Level.ERROR)).isEmpty();
            assertThat(capture.eventsAt(Level.WARN)).singleElement()
                    .satisfies(warn -> assertThat(warn.getThrowableProxy()).isNull());
        }
    }

    /**
     * An ErrorResponse answers with its own detail — never the message of its cause, which is where a
     * class name or an internal would sit.
     */
    @Test
    @DisplayName("an ErrorResponse answers with its detail, never its cause's message")
    void shouldNotLeakTheCauseOfAnErrorResponse() throws Exception {
        mockMvc.perform(get("/test/error-response"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.error").value("Content Too Large"))
                .andExpect(jsonPath("$.message").value(not(containsString("com.bitbi"))))
                .andExpect(jsonPath("$.message").value(not(containsString("Secret"))))
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))));
    }

    /**
     * A server-side ErrorResponse keeps its own status — a timed-out async request is 503, not 500 —
     * and is still logged as the server condition it is.
     */
    @Test
    @DisplayName("a server-side ErrorResponse answers its own 5xx status and is logged at ERROR")
    void shouldAnswerAServerSideErrorResponseWithItsOwnStatusAtError() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/test/async-timeout"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.error").value("Service Unavailable"))
                    .andExpect(jsonPath("$.message").value("Service Unavailable"))
                    .andExpect(jsonPath("$.path").value("/test/async-timeout"));

            assertThat(capture.eventsAt(Level.ERROR)).hasSize(1);
        }
    }

    // --- The specific handlers still win over the ErrorResponse path: all four are ErrorResponse ---

    /** Its own handler logs "Response status exception" at WARN; the ErrorResponse path would log a 503 at ERROR. */
    @Test
    @DisplayName("ResponseStatusException still goes to its own handler, even at 5xx")
    void shouldKeepResponseStatusExceptionOnItsOwnHandler() throws Exception {
        try (LogCapture capture = LogCapture.attachTo(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/test/response-status-503"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Paused for maintenance"));

            assertThat(capture.eventsAt(Level.ERROR)).isEmpty();
            assertThat(capture.eventsAt(Level.WARN)).singleElement()
                    .satisfies(warn -> assertThat(warn.getFormattedMessage()).startsWith("Response status exception"));
        }
    }

    /** Its own handler answers "Endpoint not found: …"; the detail would read "No static resource …". */
    @Test
    @DisplayName("NoResourceFoundException still goes to its own handler")
    void shouldKeepNoResourceFoundOnItsOwnHandler() throws Exception {
        mockMvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Endpoint not found: /test/no-resource"));
    }

    /** Its own handler answers the exception's message ("Request method …"); the detail reads "Method …". */
    @Test
    @DisplayName("HttpRequestMethodNotSupportedException still goes to its own handler")
    void shouldKeepMethodNotSupportedOnItsOwnHandler() throws Exception {
        mockMvc.perform(get("/test/method-not-supported"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value(containsString("Request method 'PATCH'")));
        mockMvc.perform(delete("/test/json"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value(containsString("Request method 'DELETE'")));
    }

    /** Its own handler names the field; the ErrorResponse detail is only "Invalid request content." */
    @Test
    @DisplayName("MethodArgumentNotValidException still goes to its own handler")
    void shouldKeepValidationErrorsOnTheirOwnHandler() throws Exception {
        mockMvc.perform(post("/test/named").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("name:")));
    }
}
