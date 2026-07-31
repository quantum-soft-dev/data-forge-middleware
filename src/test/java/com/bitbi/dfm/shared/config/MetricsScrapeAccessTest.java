package com.bitbi.dfm.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricsScrapeAccess}.
 * <p>
 * The allowlist is the only thing standing between the Prometheus scrape endpoints and the network,
 * so its default (no configured CIDR) must be "deny" — a deployment that forgets the property keeps
 * today's 403 rather than silently opening the metrics surface. A bad entry must not be able to
 * stop the application from booting either: metrics are a diagnostic, and taking the ingestion path
 * down to protest a typo in them defeats the point of #83.
 * </p>
 */
@DisplayName("Metrics Scrape Access Tests")
class MetricsScrapeAccessTest {

    private static boolean granted(List<String> allowedCidrs, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr(remoteAddr);
        return MetricsScrapeAccess.authorizationManager(allowedCidrs)
                .check(() -> (Authentication) null, new RequestAuthorizationContext(request))
                .isGranted();
    }

    @Test
    @DisplayName("Should deny every caller when no CIDR is configured")
    void shouldDenyWhenNoCidrConfigured() {
        assertThat(granted(List.of(), "10.4.1.7")).isFalse();
        assertThat(granted(List.of(), "127.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("Should grant a caller inside an allowed CIDR")
    void shouldGrantCallerInsideAllowedCidr() {
        assertThat(granted(List.of("10.4.0.0/14"), "10.4.1.7")).isTrue();
    }

    @Test
    @DisplayName("Should deny a caller outside every allowed CIDR")
    void shouldDenyCallerOutsideAllowedCidrs() {
        assertThat(granted(List.of("10.4.0.0/14"), "35.191.4.9")).isFalse();
    }

    @Test
    @DisplayName("Should match a single host address given without a mask")
    void shouldMatchSingleHostAddress() {
        assertThat(granted(List.of("127.0.0.1"), "127.0.0.1")).isTrue();
        assertThat(granted(List.of("127.0.0.1"), "127.0.0.2")).isFalse();
    }

    @Test
    @DisplayName("Should grant when any one of several CIDRs matches")
    void shouldGrantWhenAnyCidrMatches() {
        List<String> cidrs = List.of("10.4.0.0/14", "127.0.0.1/32");
        assertThat(granted(cidrs, "127.0.0.1")).isTrue();
        assertThat(granted(cidrs, "10.5.0.3")).isTrue();
        assertThat(granted(cidrs, "10.0.0.8")).isFalse();
    }

    @Test
    @DisplayName("Should deny an IPv6 caller that only an IPv4 entry covers, and grant it once listed")
    void shouldRequireExplicitIpv6Entry() {
        assertThat(granted(List.of("127.0.0.1/32"), "0:0:0:0:0:0:0:1")).isFalse();
        assertThat(granted(List.of("127.0.0.1/32", "::1/128"), "0:0:0:0:0:0:0:1")).isTrue();
    }

    @Test
    @DisplayName("Should ignore blank entries from a comma-separated property value")
    void shouldIgnoreBlankEntries() {
        assertThat(granted(List.of("", "  ", "10.4.0.0/14"), "10.4.0.8")).isTrue();
        assertThat(granted(List.of("", "  "), "10.4.0.8")).isFalse();
    }

    @Test
    @DisplayName("Should drop a malformed entry instead of failing, keeping the valid ones")
    void shouldDropMalformedEntry() {
        List<String> cidrs = List.of("not-an-ip", "10.4.0.0/14");
        assertThat(granted(cidrs, "10.4.0.8")).isTrue();
        assertThat(granted(cidrs, "35.191.4.9")).isFalse();
        assertThat(granted(List.of("10.4.0.0/64"), "10.4.0.8")).isFalse();
    }
}
