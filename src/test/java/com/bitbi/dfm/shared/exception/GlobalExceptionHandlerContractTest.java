package com.bitbi.dfm.shared.exception;

import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @RestController
    static class ThrowingController {

        @GetMapping("/test/unauthorized")
        public String unauthorized() {
            throw new AuthorizationHelper.UnauthorizedException("Not authenticated");
        }

        @GetMapping("/test/data-integrity")
        public String dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement; duplicate key value violates unique constraint \"uq_sites_domain\"");
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
}
