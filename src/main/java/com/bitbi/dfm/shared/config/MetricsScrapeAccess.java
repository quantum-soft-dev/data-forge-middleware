package com.bitbi.dfm.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.web.access.IpAddressAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.Objects;

/**
 * The Micrometer scrape surface and who may read it.
 * <p>
 * The delta ingestion counters live behind {@code /actuator/prometheus}, which the default filter
 * chain used to deny outright — that is why {@code delta.sessions.overflow} and friends were
 * unreachable while investigating the silent-ingestion incident (issue #83, item 5). Rather than
 * making the endpoint public, it is opened to the source addresses that legitimately scrape it: in
 * GKE the managed-Prometheus collector reaches the pod from the cluster pod range, and
 * {@code kubectl port-forward} arrives on loopback. Traffic from the external load balancer never
 * carries such a source address (it also never reaches /actuator — nginx only proxies /api), so the
 * CIDR check is a second lock rather than the only one.
 * </p>
 * <p>
 * <b>Default is deny.</b> With no CIDR configured nothing is granted, so an environment that does
 * not opt in keeps the pre-existing 403. A malformed entry is dropped with a WARN rather than
 * thrown: this is an observability knob, and failing the whole application context over it would
 * take the ingestion path down — the very thing #83 is about keeping visible. Dropping the entry
 * fails closed (that range simply stays denied).
 * </p>
 * <p>
 * <b>Address family matters.</b> Matching is per address family, so an IPv4 entry never matches an
 * IPv6 caller: an environment that can be reached over IPv6 (loopback as {@code ::1}, a dual-stack
 * pod range) has to list those forms too, or the scrape gets an unexplained 403.
 * </p>
 * <p>
 * <b>The decision reads the socket peer</b> ({@code getRemoteAddr()}), which holds only because
 * {@code server.forward-headers-strategy} is {@code none} — see the note on that property in
 * application.yml. Turning it on would let a caller name its own source address in
 * {@code X-Forwarded-For} and walk into the allowlist.
 * </p>
 *
 * @author Data Forge Team
 * @see SecurityConfiguration#defaultFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity)
 */
public final class MetricsScrapeAccess {

    private static final Logger logger = LoggerFactory.getLogger(MetricsScrapeAccess.class);

    /**
     * The endpoints the allowlist governs. Shared so the production chain and the test harness
     * cannot drift apart on which paths are opened.
     * <p>
     * {@code /actuator/metrics/**} also matches the bare {@code /actuator/metrics} index.
     * </p>
     */
    public static final String[] PATHS = {"/actuator/prometheus", "/actuator/metrics/**"};

    private MetricsScrapeAccess() {
    }

    /**
     * @param allowedCidrs CIDR blocks or single addresses allowed to scrape; blank and malformed
     *                     entries are ignored, and an empty result grants nobody
     */
    @SuppressWarnings("unchecked")
    public static AuthorizationManager<RequestAuthorizationContext> authorizationManager(List<String> allowedCidrs) {
        List<AuthorizationManager<RequestAuthorizationContext>> matchers = allowedCidrs.stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .map(MetricsScrapeAccess::matcherFor)
                .filter(Objects::nonNull)
                .toList();
        return AuthorizationManagers.anyOf(new AuthorizationDecision(false),
                matchers.toArray(new AuthorizationManager[0]));
    }

    private static AuthorizationManager<RequestAuthorizationContext> matcherFor(String cidr) {
        try {
            return IpAddressAuthorizationManager.hasIpAddress(cidr);
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring invalid entry in dfm.observability.metrics-scrape.allowed-cidrs: "
                    + "'{}' ({}). Metrics scraping stays denied for it.", cidr, e.getMessage());
            return null;
        }
    }
}
