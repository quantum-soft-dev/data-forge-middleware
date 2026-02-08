package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Batch Retention Schedule Admin API Integration Tests")
class BatchRetentionScheduleAdminControllerIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET schedule with Auth0 admin token should return effective schedule")
    void getSchedule_withAuth0_shouldReturn200() throws Exception {
        String auth0Token = "Bearer mock.admin.jwt.token";

        mockMvc.perform(get(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", auth0Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0 0 2 * * *"))
                .andExpect(jsonPath("$.source").value("CONFIG"))
                .andExpect(jsonPath("$.updatedAt").isEmpty());
    }

    @Test
    @DisplayName("PUT schedule with Auth0 admin token should persist schedule and return DB-sourced value")
    void updateSchedule_withAuth0_shouldPersistAndReturnDbValue() throws Exception {
        String auth0Token = "Bearer mock.admin.jwt.token";

        String requestBody = """
                { "cron": "0 0 3 * * *" }
                """;

        mockMvc.perform(put(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", auth0Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0 0 3 * * *"))
                .andExpect(jsonPath("$.source").value("DB"))
                .andExpect(jsonPath("$.updatedAt").exists());

        // Verify subsequent GET returns DB-sourced value.
        mockMvc.perform(get(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", auth0Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0 0 3 * * *"))
                .andExpect(jsonPath("$.source").value("DB"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("PUT schedule with invalid cron should return 400")
    void updateSchedule_invalidCron_shouldReturn400() throws Exception {
        String auth0Token = "Bearer mock.admin.jwt.token";

        String requestBody = """
                { "cron": "not-a-cron" }
                """;

        mockMvc.perform(put(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", auth0Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid cron expression")));
    }

    @Test
    @DisplayName("GET schedule with JWT token should be rejected (unauthorized)")
    void getSchedule_withJwt_shouldReturn401() throws Exception {
        String jwtToken = generateTestToken();

        mockMvc.perform(get(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", jwtToken))
                .andExpect(status().isUnauthorized());
    }
}

