package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document and Swagger UI are served (issue #302).
 * <p>
 * springdoc moved 2.8 → 3.1 with Spring Boot 4.1, and nothing else in the suite requests either
 * surface, so a springdoc that failed to register its endpoints — or a Jackson 3 mapper that could not
 * write swagger-core's model — would have shipped unseen. Outside the {@code dev} profile the document
 * is at {@code springdoc.api-docs.path} = {@code /api-docs}; {@code dev} moves it to {@code /v3/api-docs}.
 */
@DisplayName("OpenAPI docs and Swagger UI contract")
class OpenApiDocsContractTest extends BaseIntegrationTest {

    @Test
    @DisplayName("the OpenAPI document is served as JSON and describes the application's routes")
    void shouldServeOpenApiDocument() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/account/errors'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/account/errors/unread-count'].get").exists());
    }

    @Test
    @DisplayName("/swagger-ui.html redirects to the Swagger UI, which is served")
    void shouldServeSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
    }
}
