package com.bitbi.dfm.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MetricsScrapeAuthorizationManager}.
 * <p>
 * The manager is the only thing standing between the Prometheus scrape endpoints and the
 * network, so its default (no configured CIDR) must be "deny" — a deployment that forgets the
 * property keeps today's 403 rather than silently opening the metrics surface.
 * </p>
 */
@DisplayName("Metrics Scrape Authorization Manager Tests")
class MetricsScrapeAuthorizationManagerTest {

    private static AuthorizationDecision decide(List<String> allowedCidrs, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr(remoteAddr);
        return new MetricsScrapeAuthorizationManager(allowedCidrs)
                .check(() -> (Authentication) null, new RequestAuthorizationContext(request));
    }

    @Test
    @DisplayName("Should deny every caller when no CIDR is configured")
    void shouldDenyWhenNoCidrConfigured() {
        assertThat(decide(List.of(), "10.4.1.7").isGranted()).isFalse();
        assertThat(decide(List.of(), "127.0.0.1").isGranted()).isFalse();
    }

    @Test
    @DisplayName("Should grant a caller inside an allowed CIDR")
    void shouldGrantCallerInsideAllowedCidr() {
        assertThat(decide(List.of("10.0.0.0/8"), "10.4.1.7").isGranted()).isTrue();
    }

    @Test
    @DisplayName("Should deny a caller outside every allowed CIDR")
    void shouldDenyCallerOutsideAllowedCidrs() {
        assertThat(decide(List.of("10.0.0.0/8"), "35.191.4.9").isGranted()).isFalse();
    }

    @Test
    @DisplayName("Should match a single host address given without a mask")
    void shouldMatchSingleHostAddress() {
        assertThat(decide(List.of("127.0.0.1"), "127.0.0.1").isGranted()).isTrue();
        assertThat(decide(List.of("127.0.0.1"), "127.0.0.2").isGranted()).isFalse();
    }

    @Test
    @DisplayName("Should grant when any one of several CIDRs matches")
    void shouldGrantWhenAnyCidrMatches() {
        List<String> cidrs = List.of("10.4.0.0/14", "127.0.0.1/32");
        assertThat(decide(cidrs, "127.0.0.1").isGranted()).isTrue();
        assertThat(decide(cidrs, "10.5.0.3").isGranted()).isTrue();
        assertThat(decide(cidrs, "10.0.0.8").isGranted()).isFalse();
    }

    @Test
    @DisplayName("Should ignore blank entries from a comma-separated property value")
    void shouldIgnoreBlankEntries() {
        assertThat(decide(List.of("", "  ", "10.0.0.0/8"), "10.0.0.8").isGranted()).isTrue();
        assertThat(decide(List.of("", "  "), "10.0.0.8").isGranted()).isFalse();
    }

    @Test
    @DisplayName("Should reject a malformed CIDR at construction rather than at scrape time")
    void shouldRejectMalformedCidrAtConstruction() {
        assertThatThrownBy(() -> new MetricsScrapeAuthorizationManager(List.of("not-an-ip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-an-ip");
    }

    @Test
    @DisplayName("Should tolerate a null property value as no CIDR configured")
    void shouldTolerateNullPropertyValue() {
        assertThat(decide(null, "10.0.0.8").isGranted()).isFalse();
    }
}
