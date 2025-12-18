package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.PluginQueryService;
import com.bitbi.dfm.plugin.presentation.dto.AccountPluginListResponseDto;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for viewing account plugin integrations.
 *
 * <p>Handles user-facing plugin queries for authenticated accounts.
 * Requires OAuth2 authentication with accountId claim.</p>
 *
 * <p>Phase 6 (User Story 4) implementation:</p>
 * <ul>
 *   <li>GET /api/v1/account/plugins - List plugin integrations (FR-011, FR-012)</li>
 * </ul>
 *
 * @see PluginQueryService
 * @see AccountPluginListResponseDto
 */
@RestController
@RequestMapping(ApiRoutes.ACCOUNT_PLUGINS)
@Validated
@Tag(name = "Account Plugins", description = "View and manage account plugin integrations")
@SecurityRequirement(name = "oauth2")
public class AccountPluginsController {

    private static final Logger log = LoggerFactory.getLogger(AccountPluginsController.class);

    private final PluginQueryService pluginQueryService;
    private final AuthorizationHelper authorizationHelper;

    public AccountPluginsController(
            PluginQueryService pluginQueryService,
            AuthorizationHelper authorizationHelper) {
        this.pluginQueryService = pluginQueryService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Lists active plugin integrations for the authenticated account.
     *
     * <p>Implements FR-011 (list active plugins) and FR-012 (exclude sensitive plugin data).</p>
     *
     * <p>Security: Plugin-specific data (tenantId, etc.) is NOT exposed.
     * Only metadata (name, activation date, last used) is returned.</p>
     *
     * @param page page number (0-indexed)
     * @param size page size (1-100)
     * @param includeInactive whether to include deactivated plugins
     * @return paginated list of plugin summaries
     */
    @GetMapping
    @Operation(
        summary = "List account plugin integrations",
        description = """
            Returns a paginated list of plugin integrations for the authenticated account.

            **Security (FR-012):**
            - Plugin-specific data (tenantId, etc.) is NOT exposed
            - Only metadata (plugin name, activation date, last used) is returned

            **Filtering:**
            - By default, only active plugins are returned
            - Use `includeInactive=true` to include deactivated plugins
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "List of plugin integrations",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountPluginListResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Not authenticated"
        )
    })
    public ResponseEntity<AccountPluginListResponseDto> listAccountPlugins(
            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page must be 0 or greater")
            int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Size must be at least 1")
            @Max(value = 100, message = "Size must not exceed 100")
            int size,

            @Parameter(description = "Include deactivated plugins in results")
            @RequestParam(defaultValue = "false")
            boolean includeInactive) {

        UUID accountId = authorizationHelper.getAuthenticatedAccountId();
        log.info("Listing plugins for account {}, includeInactive={}, page={}, size={}",
                accountId, includeInactive, page, size);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "activatedAt"));

        AccountPluginListResponseDto response = pluginQueryService.listAccountPlugins(
                accountId, includeInactive, pageable);

        log.debug("Returning {} plugins for account {}", response.content().size(), accountId);

        return ResponseEntity.ok(response);
    }
}
