package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginActivationService.ActivationResult;
import com.bitbi.dfm.plugin.presentation.dto.ActivatePluginRequestDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginActivationResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for plugin activation operations.
 *
 * <p>Handles plugin activation and deactivation for authenticated accounts.
 * Requires OAuth2 authentication with accountId claim.</p>
 *
 * <p>Phase 3 (User Story 2) implementation:</p>
 * <ul>
 *   <li>POST /api/v1/plugins/{pluginId}/activate - Activate plugin (FR-005, FR-006)</li>
 *   <li>DELETE /api/v1/plugins/{pluginId}/deactivate - Deactivate plugin (Phase 5)</li>
 * </ul>
 *
 * @see PluginActivationService
 * @see ActivatePluginRequestDto
 * @see PluginActivationResponseDto
 */
@RestController
@RequestMapping(ApiRoutes.PLUGINS)
@Validated
@Tag(name = "Plugins", description = "Plugin activation and management operations")
@SecurityRequirement(name = "oauth2")
public class PluginController {

    private static final Logger log = LoggerFactory.getLogger(PluginController.class);

    private final PluginActivationService pluginActivationService;
    private final AuthorizationHelper authorizationHelper;

    public PluginController(
            PluginActivationService pluginActivationService,
            AuthorizationHelper authorizationHelper) {
        this.pluginActivationService = pluginActivationService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Activates a plugin for the authenticated account.
     *
     * <p>Implements upsert behavior per FR-005:</p>
     * <ul>
     *   <li>If not activated: Creates new activation record (returns 201)</li>
     *   <li>If already active: Updates pluginData and timestamps (returns 200)</li>
     *   <li>If deactivated: Reactivates with new pluginData (returns 200)</li>
     * </ul>
     *
     * @param pluginId the plugin identifier (1-64 chars, alphanumeric + hyphen)
     * @param request the activation request containing pluginData
     * @return PluginActivationResponseDto with activation status
     */
    @PostMapping("/{pluginId}/activate")
    @Operation(
        summary = "Activate a plugin",
        description = """
            Activates a plugin for the authenticated account.

            **Upsert Behavior (FR-005):**
            - If plugin is not activated: Creates new activation record (201)
            - If plugin is already active: Updates pluginData and timestamps (200)
            - If plugin was deactivated: Reactivates with new pluginData (200)

            **Validation (FR-004):**
            - pluginData is validated against the plugin's JSON Schema
            - Returns 400 if validation fails

            **Lifecycle Hook (FR-006):**
            - Calls plugin.onActivate() after successful activation
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Plugin activated successfully (new activation)",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PluginActivationResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "200",
            description = "Plugin updated successfully (existing activation)",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PluginActivationResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Validation error (invalid plugin data schema)"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Account not found for JWT accountId claim"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Plugin not found or not enabled"
        )
    })
    public ResponseEntity<PluginActivationResponseDto> activatePlugin(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable
            @Size(min = 1, max = 64, message = "Plugin ID must be 1-64 characters")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Plugin ID must be lowercase alphanumeric with hyphens")
            String pluginId,
            @Valid @RequestBody ActivatePluginRequestDto request) {

        UUID accountId = authorizationHelper.getAuthenticatedAccountId();
        log.info("Activating plugin {} for account {}", pluginId, accountId);

        ActivationResult result = pluginActivationService.activate(
            accountId,
            pluginId,
            request.pluginData()
        );

        PluginActivationResponseDto response = PluginActivationResponseDto.fromEntity(
            result.accountPlugin(),
            result.pluginDisplayName()
        );

        HttpStatus status = result.isNewActivation() ? HttpStatus.CREATED : HttpStatus.OK;
        log.debug("Plugin {} activation complete for account {}, new={}", pluginId, accountId, result.isNewActivation());

        return ResponseEntity.status(status).body(response);
    }
}
