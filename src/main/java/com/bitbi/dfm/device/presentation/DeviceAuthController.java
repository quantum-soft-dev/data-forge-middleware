package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.auth.presentation.dto.RefreshTokenRequestDto;
import com.bitbi.dfm.auth.presentation.dto.RefreshTokenResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.DeviceControllerHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Device API Authentication Controller (Auth V2).
 * <p>
 * Provides token refresh endpoint for device clients.
 * Basic Auth token generation has been removed in Auth V2.
 * Devices obtain initial tokens via Device Authorization Flow.
 * </p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 */
@RestController
@RequestMapping(ApiRoutes.DEVICE_AUTH)
@Tag(name = "Device API - Authentication", description = "Token refresh for device clients (Auth V2)")
public class DeviceAuthController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthController.class);

    private final RefreshTokenService refreshTokenService;

    public DeviceAuthController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Refresh access token using refresh token (Auth V2).
     * <p>
     * No authentication required - the refresh token itself is the credential.
     * Implements token rotation: old refresh token is revoked, new one issued.
     * </p>
     *
     * @param request refresh token request
     * @return new access token and rotated refresh token
     */
    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Issues a new JWT access token and rotated refresh token. " +
                    "The provided refresh token is revoked after use (token rotation)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token refreshed successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = RefreshTokenResponseDto.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid refresh token format"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Refresh token expired or revoked")
    })
    public ResponseEntity<?> refreshToken(
            @Valid @RequestBody RefreshTokenRequestDto request,
            HttpServletRequest httpRequest) {

        logger.debug("Token refresh request received from {}", httpRequest.getRemoteAddr());

        try {
            RefreshTokenService.RefreshResult result =
                    refreshTokenService.refreshAccessToken(request.refreshToken());

            RefreshTokenResponseDto response = new RefreshTokenResponseDto(
                    result.accessToken().token(),
                    result.refreshToken(),
                    result.accessToken().expiresAt(),
                    result.refreshTokenExpiresAt()
            );

            logger.info("Token refreshed successfully: siteId={}", result.accessToken().siteId());
            return ResponseEntity.ok(response);

        } catch (RefreshTokenService.RefreshTokenExpiredException e) {
            logger.warn("Expired refresh token from {}: {}", httpRequest.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.UNAUTHORIZED,
                            "refresh_token_expired",
                            "Refresh token has expired. Re-authorize using Device Flow.",
                            httpRequest.getRequestURI()));

        } catch (RefreshTokenService.RefreshTokenRevokedException e) {
            logger.warn("Revoked refresh token from {}: {}", httpRequest.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.UNAUTHORIZED,
                            "refresh_token_revoked",
                            "Refresh token has been revoked. Re-authorize using Device Flow.",
                            httpRequest.getRequestURI()));

        } catch (RefreshTokenService.InvalidRefreshTokenException e) {
            logger.warn("Invalid refresh token from {}: {}", httpRequest.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.BAD_REQUEST,
                            "invalid_refresh_token",
                            e.getMessage(),
                            httpRequest.getRequestURI()));

        } catch (Exception e) {
            logger.error("Unexpected error during token refresh from {}", httpRequest.getRemoteAddr(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(DeviceControllerHelper.createErrorResponse(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Internal Server Error",
                            "Internal server error",
                            httpRequest.getRequestURI()));
        }
    }
}
