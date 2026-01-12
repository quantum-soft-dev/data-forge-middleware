package com.bitbi.dfm.auth.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.presentation.dto.TokenResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for authentication operations.
 * <p>
 * Provides JWT token generation endpoint with Basic Auth.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Generate JWT token using Basic Auth credentials.
     * <p>
     * Expected Authorization header: Basic base64(domain:clientSecret)
     * </p>
     *
     * @param authHeader Authorization header with Basic Auth credentials
     * @return JWT token response
     */
    @PostMapping("/token")
    public ResponseEntity<?> generateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        logger.debug("Token generation request received");

        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            logger.warn("Missing or invalid Authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized",
                            "Missing or invalid Authorization header", request.getRequestURI()));
        }

        try {
            // Extract credentials from Basic Auth header
            String base64Credentials = authHeader.substring("Basic ".length());
            byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decodedBytes, StandardCharsets.UTF_8);

            String[] parts = credentials.split(":", 2);
            if (parts.length != 2) {
                logger.warn("Invalid Basic Auth format");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(createErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized",
                                "Invalid credentials format", request.getRequestURI()));
            }

            String domain = parts[0];
            String clientSecret = parts[1];

            // Generate JWT token
            JwtToken token = tokenService.generateToken(domain, clientSecret);

            TokenResponseDto response = TokenResponseDto.fromToken(token);

            logger.info("Token generated successfully: domain={}", domain);
            return ResponseEntity.ok(response);

        } catch (TokenService.AuthenticationException e) {
            logger.warn("Authentication failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized",
                            "Invalid credentials", request.getRequestURI()));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid Basic Auth encoding: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request",
                            "Invalid Authorization header encoding", request.getRequestURI()));

        } catch (Exception e) {
            logger.error("Unexpected error during token generation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                            "Internal server error", request.getRequestURI()));
        }
    }

    private Map<String, Object> createErrorResponse(HttpStatus status, String error, String message, String path) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", status.value());
        response.put("error", error);
        response.put("message", message);
        response.put("path", path);
        return response;
    }
}
