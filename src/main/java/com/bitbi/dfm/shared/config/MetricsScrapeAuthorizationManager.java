package com.bitbi.dfm.shared.config;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;
import java.util.function.Supplier;

/**
 * Grants access to the Micrometer scrape endpoints to callers inside a configured CIDR.
 * <p>
 * The delta ingestion counters live behind {@code /actuator/prometheus}, which the default filter
 * chain used to deny outright — that is why {@code delta.sessions.overflow} and friends were
 * unreachable while investigating the silent-ingestion incident (issue #83, item 5). Rather than
 * making the endpoint public, it is opened to the source addresses that legitimately scrape it:
 * in GKE the managed-Prometheus collector reaches the pod from the cluster pod range, and
 * {@code kubectl port-forward} arrives on loopback. Traffic from the external load balancer never
 * carries such a source address (it also never reaches /actuator — nginx only proxies /api), so a
 * CIDR check is a second lock rather than the only one.
 * </p>
 * <p>
 * <b>Default is deny.</b> With no CIDR configured the manager grants nothing, so an environment
 * that does not opt in keeps the pre-existing 403.
 * </p>
 *
 * @author Data Forge Team
 * @see SecurityConfiguration#defaultFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity)
 */
public class MetricsScrapeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final List<IpAddressMatcher> allowed;

    /**
     * @param allowedCidrs CIDR blocks or single addresses allowed to scrape; {@code null},
     *                     empty and blank entries mean "nobody"
     * @throws IllegalArgumentException if an entry is not a valid address or CIDR block
     */
    public MetricsScrapeAuthorizationManager(List<String> allowedCidrs) {
        this.allowed = allowedCidrs == null ? List.of() : allowedCidrs.stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .map(MetricsScrapeAuthorizationManager::matcherFor)
                .toList();
    }

    private static IpAddressMatcher matcherFor(String cidr) {
        try {
            return new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid CIDR in dfm.observability.metrics-scrape.allowed-cidrs: " + cidr, e);
        }
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(
                allowed.stream().anyMatch(matcher -> matcher.matches(context.getRequest())));
    }
}
