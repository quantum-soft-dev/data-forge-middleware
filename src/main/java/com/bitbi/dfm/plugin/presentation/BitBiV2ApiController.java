package com.bitbi.dfm.plugin.presentation;

import com.bitbi.dfm.plugin.application.BitBiV2QueryService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.presentation.dto.BitBiV2SiteListDto;
import com.bitbi.dfm.plugin.presentation.dto.BitBiV2SiteSchemaDto;
import com.bitbi.dfm.plugin.presentation.dto.BitBiV2SiteStateDto;
import com.bitbi.dfm.plugin.presentation.dto.SiteDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bit BI plugin API v2, read surface part 1 (issue #386; plan in
 * {@code docs/cr-bitbi-parquet-plugin.md}, phase 1).
 *
 * <p>A versioned surface beside {@link BitBiPluginApiController}, not a fork: the same
 * {@code bit-bi} activation and API key, the same per-account rate-limit bucket (v1 and v2 calls
 * draw from one bucket), and the read services the rest of the application uses. The consumer
 * holds its own watermark; nothing here reads {@code baseline_batch_id} or
 * {@code plugin_delta_baselines}.</p>
 *
 * <p>A site of another account answers 404 exactly like one that does not exist (v1 answers 403).
 * A 429 carries {@code Retry-After} and the standard error body.</p>
 */
@RestController
@RequestMapping(ApiRoutes.BITBI_V2_API)
@Tag(name = "Bit BI Plugin API v2", description = "Parquet-based read surface for Bit BI")
@SecurityRequirement(name = "PluginApiKey")
public class BitBiV2ApiController {

    private final BitBiV2QueryService queryService;
    private final PluginRateLimiterService rateLimiterService;

    public BitBiV2ApiController(BitBiV2QueryService queryService, PluginRateLimiterService rateLimiterService) {
        this.queryService = queryService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping(value = "/sites", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List the account's sites", description = "The account's active sites, as v1's /sites.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sites",
                    content = @Content(schema = @Schema(implementation = BitBiV2SiteListDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked API key"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<?> listSites(Authentication authentication, HttpServletRequest request) {
        UUID accountId = accountId(authentication);
        return limited(accountId, request, () -> new BitBiV2SiteListDto(queryService.listSites(accountId).stream()
                .map(site -> SiteDto.of(site.getId(), site.getSiteName(), site.getDisplayName()))
                .toList()));
    }

    @GetMapping(value = "/sites/{siteId}/state", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A site's position",
            description = "epoch (moves on a history wipe and on a re-baseline), checkpointSeq, lastAppliedSeq "
                    + "and schemaVersion. A changed epoch means the consumer's mirror belongs to a discarded "
                    + "history; a moved schemaVersion means /schema must be read again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The site's state",
                    content = @Content(schema = @Schema(implementation = BitBiV2SiteStateDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked API key"),
            @ApiResponse(responseCode = "404", description = "No such site for this account",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<?> state(
            @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId,
            Authentication authentication, HttpServletRequest request) {
        UUID accountId = accountId(authentication);
        return limited(accountId, request, () -> BitBiV2SiteStateDto.from(queryService.state(accountId, siteId)));
    }

    @GetMapping(value = "/sites/{siteId}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A site's declared schema with row identity",
            description = "Per table: identity (row_hash when no primary key is declared — every DBF table — "
                    + "otherwise primary_key), primaryKey, uniqueKeys and columns, as declared in SubmitSchema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The site's schema",
                    content = @Content(schema = @Schema(implementation = BitBiV2SiteSchemaDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked API key"),
            @ApiResponse(responseCode = "404", description = "No such site for this account",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<?> schema(
            @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId,
            Authentication authentication, HttpServletRequest request) {
        UUID accountId = accountId(authentication);
        return limited(accountId, request, () -> BitBiV2SiteSchemaDto.from(queryService.schema(accountId, siteId)));
    }

    /**
     * Spend one token of the account's bucket before any read, as v1 does, then answer.
     */
    private ResponseEntity<?> limited(UUID accountId, HttpServletRequest request, Supplier<Object> body) {
        if (!rateLimiterService.tryConsume(accountId)) {
            long retryAfter = rateLimiterService.getRetryAfterSeconds(accountId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(new ErrorResponseDto(Instant.now(), HttpStatus.TOO_MANY_REQUESTS.value(),
                            "Too Many Requests", "Rate limit exceeded. Retry after " + retryAfter + " seconds.",
                            request.getRequestURI()));
        }
        return ResponseEntity.ok(body.get());
    }

    private static UUID accountId(Authentication authentication) {
        if (authentication instanceof PluginApiKeyAuthenticationFilter.PluginApiKeyAuthenticationToken token) {
            return token.getAccountId();
        }
        throw new IllegalStateException("Expected PluginApiKeyAuthenticationToken but got: "
                + (authentication == null ? "null" : authentication.getClass().getName()));
    }
}
