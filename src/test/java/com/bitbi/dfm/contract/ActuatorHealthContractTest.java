package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for Actuator health endpoints.
 * <p>
 * The Kubernetes liveness/readiness probes hit {@code /actuator/health/liveness} and
 * {@code /actuator/health/readiness}. Those probe sub-paths (exposed when
 * {@code management.endpoint.health.probes.enabled=true}) must be reachable WITHOUT
 * authentication — otherwise Spring Security returns 403 and the pod never becomes ready.
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration
 */
@DisplayName("Actuator Health Contract Tests")
class ActuatorHealthContractTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should permit /actuator/health without authentication")
    void shouldPermitHealthWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should permit /actuator/health/liveness probe without authentication")
    void shouldPermitLivenessProbeWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should permit /actuator/health/readiness probe without authentication")
    void shouldPermitReadinessProbeWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
