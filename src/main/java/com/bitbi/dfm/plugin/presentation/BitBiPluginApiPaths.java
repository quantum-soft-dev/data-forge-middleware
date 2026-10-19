package com.bitbi.dfm.plugin.presentation;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Which requests the Bit BI plugin API key authenticates (issue #384).
 *
 * <p>The whole {@code /api/v1/plugins/bit-bi/**} namespace, minus the two routes an account owner
 * reaches with an Auth0 token: {@code /activate} and {@code /deactivate}
 * ({@code PluginController}, mapped as {@code /api/v1/plugins/{pluginId}/...}). Owning the
 * namespace rather than a list of today's routes is what makes an unknown path answer like a known
 * one: 401 without a valid key, 404 with one. A route list sent every unlisted path — a v2 route
 * that did not exist yet, a typo — to the Auth0 chain, where a client holding a perfectly good key
 * was told it was not authenticated (or, before #384, that it was forbidden), and read that as a
 * verdict on its key. It also means a new key-authenticated route needs only its controller.</p>
 *
 * <p>One matcher, three readers, so they cannot drift: the plugin chain's {@code securityMatcher}
 * and the OAuth chain's {@code denyAll} in {@code SecurityConfiguration} (and in the test profile's
 * {@code TestSecurityConfig}), and {@link PluginApiKeyAuthenticationFilter#shouldNotFilter}. A new
 * <b>Auth0</b> route under this namespace must be added to the exclusion here, or it will demand a
 * plugin key; {@code ProductionSecurityChainTest} pins the two that exist.</p>
 */
public final class BitBiPluginApiPaths {

    /** The namespace the plugin key owns. */
    public static final String NAMESPACE = "/api/v1/plugins/bit-bi/**";

    /** Auth0-authenticated routes inside the namespace. */
    private static final RequestMatcher AUTH0_ROUTES = new OrRequestMatcher(
            path("/api/v1/plugins/bit-bi/activate"),
            path("/api/v1/plugins/bit-bi/deactivate"));

    /** Requests the plugin API key authenticates. */
    public static final RequestMatcher KEY_AUTHENTICATED = new AndRequestMatcher(
            path(NAMESPACE),
            new NegatedRequestMatcher(AUTH0_ROUTES));

    private BitBiPluginApiPaths() {
    }

    private static RequestMatcher path(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }
}
