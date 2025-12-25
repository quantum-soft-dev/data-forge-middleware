package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginActivationService.ActivationResult;
import com.bitbi.dfm.plugin.application.PluginQueryService;
import com.bitbi.dfm.plugin.presentation.dto.ActivatePluginRequestDto;
import com.bitbi.dfm.plugin.presentation.dto.AvailablePluginResponseDto;
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

import java.util.List;
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
    private final PluginQueryService pluginQueryService;
    private final AuthorizationHelper authorizationHelper;

    public PluginController(
            PluginActivationService pluginActivationService,
            PluginQueryService pluginQueryService,
            AuthorizationHelper authorizationHelper) {
        this.pluginActivationService = pluginActivationService;
        this.pluginQueryService = pluginQueryService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Lists available plugins for activation.
     *
     * <p>Returns all enabled plugins that can be activated by users.
     * Only public fields are returned - no sensitive data like client_id.</p>
     *
     * @return list of available plugins
     */
    @GetMapping
    @Operation(
        summary = "List available plugins",
        description = "Returns all enabled plugins that can be activated by users"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "List of available plugins",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AvailablePluginResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        )
    })
    public ResponseEntity<List<AvailablePluginResponseDto>> listAvailablePlugins() {
        log.debug("Listing available plugins");

        List<AvailablePluginResponseDto> plugins = pluginQueryService.listAvailablePlugins();

        log.info("Returned {} available plugins", plugins.size());
        return ResponseEntity.ok(plugins);
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

    /**
     * Deactivates a plugin for the authenticated account.
     *
     * <p>Sets the plugin activation to inactive status. The activation record
     * is preserved for audit purposes but the plugin will no longer receive events.</p>
     *
     * @param pluginId the plugin identifier (1-64 chars, alphanumeric + hyphen)
     * @return 204 No Content on success
     */
    @DeleteMapping("/{pluginId}/deactivate")
    @Operation(
        summary = "Deactivate a plugin",
        description = """
            Deactivates a plugin for the authenticated account.

            **Behavior:**
            - Sets is_active=false and records deactivated_at timestamp
            - Plugin will no longer receive events for this account
            - Activation record is preserved for audit purposes

            **Lifecycle Hook (FR-006):**
            - Calls plugin.onDeactivate() after successful deactivation
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "Plugin deactivated successfully"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Plugin not activated for this account"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Plugin not found"
        )
    })
    public ResponseEntity<Void> deactivatePlugin(
            @Parameter(description = "Plugin identifier", example = "bit-bi")
            @PathVariable
            @Size(min = 1, max = 64, message = "Plugin ID must be 1-64 characters")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Plugin ID must be lowercase alphanumeric with hyphens")
            String pluginId) {

        UUID accountId = authorizationHelper.getAuthenticatedAccountId();
        log.info("Deactivating plugin {} for account {}", pluginId, accountId);

        pluginActivationService.deactivate(accountId, pluginId);

        log.debug("Plugin {} deactivation complete for account {}", pluginId, accountId);

        return ResponseEntity.noContent().build();
    }
}
