package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the Prometheus scrape surface (issue #83, item 5).
 * <p>
 * The delta ingestion counters ({@code delta.sessions.started}, {@code delta.sessions.overflow},
 * …) were unreachable because the default filter chain denied everything under /actuator except
 * health and info. They are now reachable — but only from the CIDRs listed in
 * {@code dfm.observability.metrics-scrape.allowed-cidrs}, which in the cluster is the pod range
 * the managed-Prometheus collector scrapes from.
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.MetricsScrapeAccess
 */
@DisplayName("Metrics Scrape Contract Tests")
// Spring Boot disables metrics export under test, which would leave /actuator/prometheus absent
// (404) and make this suite prove nothing about the endpoint the collector actually scrapes.
@AutoConfigureObservability
@TestPropertySource(properties = "dfm.observability.metrics-scrape.allowed-cidrs=127.0.0.1/32")
class MetricsScrapeContractTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should serve /actuator/prometheus with the delta counters to an allowed caller")
    void shouldExposeDeltaCountersInScrapePayload() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("delta_sessions_started_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("delta_sessions_overflow_total")));
    }

    @Test
    @DisplayName("Should serve /actuator/metrics to a caller inside an allowed CIDR")
    void shouldServeMetricsToAllowedCaller() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should deny /actuator/prometheus to a caller outside every allowed CIDR")
    void shouldDenyPrometheusToForeignCaller() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(request -> {
                    request.setRemoteAddr("35.191.4.9");
                    return request;
                }))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should keep the rest of the actuator surface denied even from an allowed CIDR")
    void shouldKeepOtherActuatorEndpointsDenied() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isForbidden());
    }
}
