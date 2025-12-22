package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication filter for Plugin API Key authentication.
 *
 * <p>This filter processes requests to the Bit BI Plugin API (/api/v1/plugins/bit-bi/**)
 * and validates the API key provided in the X-Plugin-Api-Key header.</p>
 *
 * <p>If the API key is valid, it sets the security context with a PluginApiKeyAuthenticationToken
 * containing the account ID associated with the API key.</p>
 *
 * @see PluginApiKeyService
 */
@Component
public class PluginApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PluginApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-Plugin-Api-Key";
    private static final String PLUGIN_API_PATH = "/api/v1/plugins/bit-bi";

    private final PluginApiKeyService pluginApiKeyService;
    private final ObjectMapper objectMapper;

    public PluginApiKeyAuthenticationFilter(
            PluginApiKeyService pluginApiKeyService,
            ObjectMapper objectMapper) {
        this.pluginApiKeyService = pluginApiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(PLUGIN_API_PATH);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Extract API key from header
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No API key found in request to {}", path);
            writeUnauthorizedResponse(response, path);
            return;
        }

        try {
            // Validate API key
            Optional<AccountPlugin> accountPluginOpt = pluginApiKeyService.validateApiKey(apiKey);

            if (accountPluginOpt.isEmpty()) {
                log.warn("Invalid API key used for path {}", path);
                writeUnauthorizedResponse(response, path);
                return;
            }

            AccountPlugin accountPlugin = accountPluginOpt.get();

            // Create authentication token
            PluginApiKeyAuthenticationToken authentication = new PluginApiKeyAuthenticationToken(
                    accountPlugin.getAccountId(),
                    accountPlugin.getId(),
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_PLUGIN_CLIENT"))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // Set authentication in security context
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("Plugin API key authentication successful: accountId={}, accountPluginId={}",
                    accountPlugin.getAccountId(), accountPlugin.getId());

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("Error during Plugin API key authentication: {}", e.getMessage(), e);
            writeUnauthorizedResponse(response, path);
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "error", "Unauthorized",
                "message", "Invalid or missing API key",
                "path", path
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }

    /**
     * Authentication token for Plugin API Key authenticated requests.
     *
     * <p>Stores accountId and accountPluginId for authorization checks.</p>
     */
    public static class PluginApiKeyAuthenticationToken extends UsernamePasswordAuthenticationToken {
        private final UUID accountId;
        private final Long accountPluginId;

        public PluginApiKeyAuthenticationToken(
                UUID accountId,
                Long accountPluginId,
                java.util.List<SimpleGrantedAuthority> authorities) {
            super(accountId, null, authorities);
            this.accountId = accountId;
            this.accountPluginId = accountPluginId;
        }

        public UUID getAccountId() {
            return accountId;
        }

        public Long getAccountPluginId() {
            return accountPluginId;
        }
    }
}
