package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.application.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/**
 * JWT authentication filter for custom JWT tokens.
 * <p>
 * This filter validates JWT tokens for client API endpoints (/api/v1/**)
 * and sets the security context with authenticated user details.
 * </p>
 * <p>
 * Token format: Authorization: Bearer {token}
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public JwtAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Only process /api/v1/** endpoints (except /api/v1/auth/token which uses Basic Auth)
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") || path.equals("/api/v1/auth/token")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract JWT token from Authorization header
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            logger.debug("No JWT token found in request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            // Validate token and get site ID
            UUID siteId = tokenService.validateToken(token);
            UUID accountId = tokenService.extractAccountId(token);
            String domain = tokenService.extractDomain(token);

            // Create authentication object with site ID as principal
            JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                    siteId,
                    accountId,
                    domain,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_CLIENT"))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // Set authentication in security context
            SecurityContextHolder.getContext().setAuthentication(authentication);

            logger.debug("JWT authentication successful: siteId={}, accountId={}, domain={}", siteId, accountId, domain);

        } catch (TokenService.InvalidTokenException e) {
            logger.warn("JWT token validation failed for path {}: {}", path, e.getMessage());
            // Don't set authentication - Spring Security will return 401/403
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Custom authentication token for JWT-authenticated requests.
     * <p>
     * Stores siteId, accountId, and domain for authorization checks.
     * Token is pre-authenticated (created with authorities in constructor).
     * </p>
     */
    public static class JwtAuthenticationToken extends UsernamePasswordAuthenticationToken {
        private final UUID siteId;
        private final UUID accountId;
        private final String domain;

        public JwtAuthenticationToken(UUID siteId, UUID accountId, String domain, java.util.List<SimpleGrantedAuthority> authorities) {
            // Use constructor that accepts authorities - this automatically sets authenticated=true
            super(siteId, null, authorities);
            this.siteId = siteId;
            this.accountId = accountId;
            this.domain = domain;
            // Do NOT call setAuthenticated(true) - already handled by super constructor
        }

        public UUID getSiteId() {
            return siteId;
        }

        public UUID getAccountId() {
            return accountId;
        }

        public String getDomain() {
            return domain;
        }
    }
}