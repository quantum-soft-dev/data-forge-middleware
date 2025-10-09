package com.bitbi.dfm.shared.auth;

import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Filter to detect and reject requests with multiple authentication tokens.
 *
 * This filter runs before Spring Security authentication filters and checks
 * if a request contains both an internal JWT token and a Keycloak token.
 * If both are present, it returns a 400 Bad Request response with an ErrorResponseDto.
 *
 * FR-015: Dual token detection and rejection
 */
@Component
public class DualAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public DualAuthenticationFilter() {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Check for Authorization header (JWT or Keycloak)
        String authorizationHeader = request.getHeader("Authorization");

        // Check for X-Keycloak-Token header (alternative Keycloak token header)
        String keycloakTokenHeader = request.getHeader("X-Keycloak-Token");

        // If both headers are present, reject with 400 Bad Request
        if (authorizationHeader != null && keycloakTokenHeader != null) {
            ErrorResponseDto errorResponse = new ErrorResponseDto(
                    Instant.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Bad Request",
                    "Ambiguous authentication: multiple tokens provided",
                    request.getRequestURI()
            );

            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return;
        }

        // Continue filter chain
        filterChain.doFilter(request, response);
    }
}
