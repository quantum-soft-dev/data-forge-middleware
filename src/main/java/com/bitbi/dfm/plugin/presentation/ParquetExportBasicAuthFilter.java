package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP Basic authentication for the Parquet Export plugin listing API (028).
 *
 * <p>Applies only to {@code /api/v1/plugins/parquet-export/files}: the download route is
 * deliberately anonymous — its one-time token is the credential. Successful authentication
 * places a {@link PluginApiKeyAuthenticationFilter.PluginApiKeyAuthenticationToken} in the
 * security context (same shape as Bit BI's API-key auth: principal = accountId, detail =
 * accountPluginId, authority ROLE_PLUGIN_CLIENT).</p>
 */
@Component
public class ParquetExportBasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ParquetExportBasicAuthFilter.class);

    private static final String FILES_PATH = "/api/v1/plugins/parquet-export/files";
    private static final String CHALLENGE = "Basic realm=\"parquet-export\"";

    private final ParquetExportCredentialsService credentialsService;
    private final ObjectMapper objectMapper;

    public ParquetExportBasicAuthFilter(ParquetExportCredentialsService credentialsService,
                                        ObjectMapper objectMapper) {
        this.credentialsService = credentialsService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(FILES_PATH) || path.startsWith(FILES_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            writeUnauthorizedResponse(response, request.getRequestURI());
            return;
        }

        String login;
        String password;
        try {
            String userinfo = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = userinfo.indexOf(':');
            if (colon < 0) {
                writeUnauthorizedResponse(response, request.getRequestURI());
                return;
            }
            login = userinfo.substring(0, colon);
            password = userinfo.substring(colon + 1); // password may itself contain colons
        } catch (IllegalArgumentException e) {
            writeUnauthorizedResponse(response, request.getRequestURI());
            return;
        }

        try {
            Optional<AccountPlugin> accountPluginOpt = credentialsService.validate(login, password);
            if (accountPluginOpt.isEmpty()) {
                log.warn("Invalid parquet-export Basic Auth attempt for login={}", login);
                writeUnauthorizedResponse(response, request.getRequestURI());
                return;
            }

            AccountPlugin accountPlugin = accountPluginOpt.get();
            var authentication = new PluginApiKeyAuthenticationFilter.PluginApiKeyAuthenticationToken(
                    accountPlugin.getAccountId(),
                    accountPlugin.getId(),
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_PLUGIN_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error during parquet-export Basic authentication: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            writeUnauthorizedResponse(response, request.getRequestURI());
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "error", "Unauthorized",
                "message", "Invalid or missing Basic Auth credentials",
                "path", path)));
    }
}
