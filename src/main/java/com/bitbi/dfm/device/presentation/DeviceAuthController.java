package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.presentation.dto.TokenResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.DeviceControllerHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Device API Authentication Controller.
 * <p>
 * Provides JWT token generation for device clients (IoT devices, mobile apps,
 * data collection clients) using Basic Authentication with site credentials.
 * </p>
 * <p>
 * <b>Authentication</b>: Basic Auth (domain:clientSecret)<br>
 * <b>Returns</b>: Custom JWT token for subsequent Device API requests
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.auth.application.TokenService
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@RestController
@RequestMapping(ApiRoutes.DEVICE_AUTH)
@Tag(name = "Device API - Authentication", description = "JWT token generation for device clients using Basic Auth (domain:clientSecret)")
public class DeviceAuthController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthController.class);

    private final TokenService tokenService;

    /**
     * Constructor injection for TokenService.
     *
     * @param tokenService Service for generating JWT tokens
     */
    public DeviceAuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Generate JWT token using Basic Auth credentials.
     * <p>
     * <b>Authentication</b>: Basic Auth with site domain and clientSecret<br>
     * <b>Format</b>: Authorization: Basic base64(domain:clientSecret)<br>
     * <b>Returns</b>: Custom JWT token valid for 24 hours
     * </p>
     *
     * @param authHeader Authorization header with Basic Auth credentials
     * @param request    HTTP servlet request for error reporting
     * @return JWT token response or error response
     */
    @PostMapping("/token")
    @Operation(
            summary = "Generate JWT token for device authentication",
            description = "Issues a Custom JWT token for device clients using Basic Authentication with site credentials. " +
                    "The credentials format is 'domain:clientSecret' encoded in Base64."
    )
    @SecurityRequirement(name = "basicAuth")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "JWT token generated successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TokenResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Invalid Authorization header encoding",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Invalid credentials or inactive site",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal Server Error",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> generateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        logger.debug("Device API token generation request received from {}", request.getRemoteAddr());

        // Validate Authorization header presence
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            logger.warn("Missing or invalid Authorization header from {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.UNAUTHORIZED,
                            "Unauthorized",
                            "Missing or invalid Authorization header",
                            request.getRequestURI()));
        }

        try {
            // Extract and decode Basic Auth credentials
            String base64Credentials = authHeader.substring("Basic ".length());
            byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decodedBytes, StandardCharsets.UTF_8);

            // Parse domain:clientSecret format
            String[] parts = credentials.split(":", 2);
            if (parts.length != 2) {
                logger.warn("Invalid Basic Auth format from {}", request.getRemoteAddr());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(DeviceControllerHelper.createErrorResponse(
                                HttpStatus.UNAUTHORIZED,
                                "Unauthorized",
                                "Invalid credentials format",
                                request.getRequestURI()));
            }

            String domain = parts[0];
            String clientSecret = parts[1];

            // Generate JWT token via TokenService
            JwtToken token = tokenService.generateToken(domain, clientSecret);

            // Convert to DTO and return
            TokenResponseDto response = TokenResponseDto.fromToken(token);

            logger.info("Device API token generated successfully: domain={}, siteId={}",
                    domain, token.siteId());

            return ResponseEntity.ok(response);

        } catch (TokenService.AuthenticationException e) {
            // Invalid credentials, inactive site, or non-existent domain
            logger.warn("Device API authentication failed from {}: {}",
                    request.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.UNAUTHORIZED,
                            "Unauthorized",
                            "Invalid credentials",
                            request.getRequestURI()));

        } catch (IllegalArgumentException e) {
            // Invalid Base64 encoding
            logger.warn("Invalid Basic Auth encoding from {}: {}",
                    request.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.BAD_REQUEST,
                            "Bad Request",
                            "Invalid Authorization header encoding",
                            request.getRequestURI()));

        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error during Device API token generation from {}",
                    request.getRemoteAddr(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Internal Server Error",
                            "Internal server error",
                            request.getRequestURI()));
        }
    }
}
