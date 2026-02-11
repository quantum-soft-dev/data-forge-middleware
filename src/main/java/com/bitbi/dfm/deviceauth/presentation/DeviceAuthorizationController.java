package com.bitbi.dfm.deviceauth.presentation;

import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService;
import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.AuthorizationInfo;
import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.AuthorizationResult;
import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.TokenResult;
import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.VerificationResult;
import com.bitbi.dfm.deviceauth.presentation.dto.*;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for Device Authorization Flow (RFC 8628 adapted).
 * <p>
 * Implements Device Authorization Grant for site creation workflow:
 * - Device initiates authorization with siteName/siteDescription
 * - User approves via web interface
 * - Site is created automatically on approval
 * - Device receives credentials via polling
 * </p>
 * <p>
 * Public endpoints: POST /authorize, POST /token<br>
 * Protected endpoints (Auth0): GET/POST/DELETE /verify
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8628">RFC 8628 - Device Authorization Grant</a>
 */
@RestController
@RequestMapping("/api/v1/device")
@Tag(name = "Device Authorization", description = "Device Authorization Flow for site creation")
public class DeviceAuthorizationController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthorizationController.class);

    private final DeviceAuthorizationService deviceAuthorizationService;
    private final AuthorizationHelper authorizationHelper;

    public DeviceAuthorizationController(DeviceAuthorizationService deviceAuthorizationService,
                                         AuthorizationHelper authorizationHelper) {
        this.deviceAuthorizationService = deviceAuthorizationService;
        this.authorizationHelper = authorizationHelper;
    }

    // ==================== Public Endpoints ====================

    /**
     * Initiate device authorization flow.
     * <p>
     * POST /api/v1/device/authorize (Public)
     * </p>
     * <p>
     * Device calls this endpoint to start authorization, providing site details.
     * Returns device code (for polling) and user code (for user to enter).
     * </p>
     *
     * @param request authorization request with siteName and optional siteDescription
     * @return authorization response with codes and verification URI
     */
    @PostMapping("/authorize")
    @Operation(
            summary = "Initiate device authorization",
            description = "Device initiates authorization flow with site creation data. " +
                    "Returns device code for polling and user code for user verification."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Authorization initiated successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceAuthorizationResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeviceAuthorizationResponseDto> authorize(
            @Valid @RequestBody DeviceAuthorizationRequestDto request) {

        logger.info("Device authorization request: siteName={}", request.siteName());

        AuthorizationResult result = deviceAuthorizationService.authorize(
                request.siteName(),
                request.siteDescription()
        );

        DeviceAuthorizationResponseDto response = DeviceAuthorizationResponseDto.fromResult(result);

        logger.info("Device authorization initiated: userCode={}", result.userCode());
        return ResponseEntity.ok(response);
    }

    /**
     * Poll for authorization token (device side).
     * <p>
     * POST /api/v1/device/token (Public)
     * </p>
     * <p>
     * Device polls this endpoint to check if user has approved.
     * Returns credentials on success, or error status (pending, denied, expired).
     * </p>
     *
     * @param request token request with deviceCode
     * @return token response with credentials, or error response
     */
    @PostMapping("/token")
    @Operation(
            summary = "Poll for authorization token",
            description = "Device polls this endpoint to check authorization status. " +
                    "Returns site credentials on approval, or error (pending/denied/expired)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Authorization approved, credentials returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceTokenResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Authorization pending, denied, or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceTokenErrorDto.class)))
    })
    public ResponseEntity<?> getToken(@Valid @RequestBody DeviceTokenRequestDto request) {

        logger.debug("Token poll: deviceCode={}...",
                request.deviceCode().substring(0, Math.min(8, request.deviceCode().length())));

        TokenResult result = deviceAuthorizationService.getToken(request.deviceCode());

        return switch (result) {
            case TokenResult.Success success -> {
                logger.info("Token issued: siteId={}, siteName={}", success.siteId(), success.siteName());
                yield ResponseEntity.ok(DeviceTokenResponseDto.fromResult(success));
            }
            case TokenResult.Pending pending -> {
                logger.debug("Authorization pending");
                yield ResponseEntity.badRequest().body(DeviceTokenErrorDto.authorizationPending());
            }
            case TokenResult.AccessDenied denied -> {
                logger.info("Authorization denied");
                yield ResponseEntity.badRequest().body(DeviceTokenErrorDto.accessDenied());
            }
            case TokenResult.ExpiredToken expired -> {
                logger.info("Authorization expired");
                yield ResponseEntity.badRequest().body(DeviceTokenErrorDto.expiredToken());
            }
            case TokenResult.InvalidGrant invalid -> {
                logger.warn("Invalid device code");
                yield ResponseEntity.badRequest().body(DeviceTokenErrorDto.invalidGrant());
            }
        };
    }

    // ==================== Protected Endpoints (Auth0) ====================

    /**
     * Get authorization info for verification page.
     * <p>
     * GET /api/v1/device/verify?code=XXXX-XXXX (Auth0 Protected)
     * </p>
     * <p>
     * User opens this URL to see what site will be created.
     * </p>
     *
     * @param code user code from device
     * @return authorization info (siteName, siteDescription, expiresAt)
     */
    @GetMapping("/verify")
    @SecurityRequirement(name = "oauth2")
    @Operation(
            summary = "Get authorization info",
            description = "User retrieves authorization details to review before approving. " +
                    "Requires Auth0 authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Authorization info retrieved",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceVerifyInfoResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Authorization already processed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Authorization not found or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeviceVerifyInfoResponseDto> getVerificationInfo(
            @RequestParam("code") String code) {

        // Validate authentication
        authorizationHelper.getAuthenticatedAccountId();

        logger.debug("Verification info requested: userCode={}", code);

        try {
            AuthorizationInfo info = deviceAuthorizationService.getAuthorizationInfo(code);

            DeviceVerifyInfoResponseDto response = DeviceVerifyInfoResponseDto.fromInfo(info);

            logger.debug("Verification info returned: userCode={}, siteName={}",
                    code, info.siteName());
            return ResponseEntity.ok(response);

        } catch (DeviceAuthorizationService.AuthorizationNotFoundException e) {
            logger.warn("Authorization not found: userCode={}", code);
            return ResponseEntity.notFound().build();
        } catch (DeviceAuthorizationService.AuthorizationExpiredException e) {
            logger.warn("Authorization expired: userCode={}", code);
            return ResponseEntity.notFound().build();
        } catch (DeviceAuthorizationService.AuthorizationAlreadyProcessedException e) {
            logger.warn("Authorization already processed: userCode={}", code);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Approve authorization and create site.
     * <p>
     * POST /api/v1/device/verify (Auth0 Protected)
     * </p>
     * <p>
     * User approves the authorization. Site is created automatically.
     * Device will receive credentials on next poll.
     * </p>
     *
     * @param request verification request with userCode and action
     * @return verification result with created site info
     */
    @PostMapping("/verify")
    @SecurityRequirement(name = "oauth2")
    @Operation(
            summary = "Approve or deny authorization",
            description = "User approves or denies the authorization request. " +
                    "On approval, site is created automatically. Requires Auth0 authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Authorization processed successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceVerifyResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid action or authorization already processed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Authorization not found or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeviceVerifyResponseDto> verify(
            @Valid @RequestBody DeviceVerifyRequestDto request) {

        UUID accountId = authorizationHelper.getAuthenticatedAccountId();

        logger.info("Verification request: userCode={}, action={}, accountId={}",
                request.userCode(), request.action(), accountId);

        try {
            if ("approve".equalsIgnoreCase(request.action())) {
                VerificationResult result = deviceAuthorizationService.verify(
                        request.userCode(),
                        accountId
                );

                logger.info("Authorization approved: userCode={}, siteId={}, siteName={}",
                        request.userCode(), result.siteId(), result.siteName());

                return ResponseEntity.ok(DeviceVerifyResponseDto.success(
                        result.siteId(),
                        result.siteName()
                ));

            } else if ("deny".equalsIgnoreCase(request.action())) {
                deviceAuthorizationService.deny(request.userCode());

                logger.info("Authorization denied: userCode={}", request.userCode());

                return ResponseEntity.ok(DeviceVerifyResponseDto.denied());

            } else {
                logger.warn("Invalid action: {}", request.action());
                return ResponseEntity.badRequest().build();
            }

        } catch (DeviceAuthorizationService.AuthorizationNotFoundException e) {
            logger.warn("Authorization not found: userCode={}", request.userCode());
            return ResponseEntity.notFound().build();
        } catch (DeviceAuthorizationService.AuthorizationExpiredException e) {
            logger.warn("Authorization expired: userCode={}", request.userCode());
            return ResponseEntity.notFound().build();
        } catch (DeviceAuthorizationService.AuthorizationAlreadyProcessedException e) {
            logger.warn("Authorization already processed: userCode={}", request.userCode());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Deny authorization request.
     * <p>
     * DELETE /api/v1/device/verify (Auth0 Protected)
     * </p>
     * <p>
     * Alternative method to deny authorization (for RESTful semantics).
     * </p>
     *
     * @param userCode user code to deny
     * @return success response
     */
    @DeleteMapping("/verify")
    @SecurityRequirement(name = "oauth2")
    @Operation(
            summary = "Deny authorization",
            description = "User denies the authorization request. " +
                    "Alternative to POST /verify with action=deny. Requires Auth0 authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Authorization denied",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeviceVerifyResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Authorization already processed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Authorization not found or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<DeviceVerifyResponseDto> deny(
            @RequestParam("code") String userCode) {

        // Validate authentication
        authorizationHelper.getAuthenticatedAccountId();

        logger.info("Deny request: userCode={}", userCode);

        try {
            deviceAuthorizationService.deny(userCode);

            logger.info("Authorization denied via DELETE: userCode={}", userCode);

            return ResponseEntity.ok(DeviceVerifyResponseDto.denied());

        } catch (DeviceAuthorizationService.AuthorizationNotFoundException e) {
            logger.warn("Authorization not found: userCode={}", userCode);
            return ResponseEntity.notFound().build();
        } catch (DeviceAuthorizationService.AuthorizationAlreadyProcessedException e) {
            logger.warn("Authorization already processed: userCode={}", userCode);
            return ResponseEntity.badRequest().build();
        }
    }
}
