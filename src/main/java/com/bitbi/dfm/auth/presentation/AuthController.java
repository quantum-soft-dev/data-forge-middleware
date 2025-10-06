package com.bitbi.dfm.auth.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
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
    public ResponseEntity<Map<String, Object>> generateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        logger.debug("Token generation request received");

        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            logger.warn("Missing or invalid Authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("Missing or invalid Authorization header"));
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
                        .body(createErrorResponse("Invalid credentials format"));
            }

            String domain = parts[0];
            String clientSecret = parts[1];

            // Generate JWT token
            JwtToken token = tokenService.generateToken(domain, clientSecret);

            Map<String, Object> response = new HashMap<>();
            response.put("token", token.token());
            response.put("expiresAt", token.expiresAt().toString());
            response.put("tokenType", "Bearer");

            logger.info("Token generated successfully: domain={}", domain);
            return ResponseEntity.ok(response);

        } catch (TokenService.AuthenticationException e) {
            logger.warn("Authentication failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("Invalid credentials"));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid Basic Auth encoding: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Invalid Authorization header encoding"));

        } catch (Exception e) {
            logger.error("Unexpected error during token generation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Internal server error"));
        }
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
