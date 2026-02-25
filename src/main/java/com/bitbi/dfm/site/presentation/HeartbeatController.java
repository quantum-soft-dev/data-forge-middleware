package com.bitbi.dfm.site.presentation;

import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.site.application.HeartbeatService;
import com.bitbi.dfm.site.presentation.dto.HeartbeatResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for heartbeat pre-batch sync check (Device API).
 * <p>
 * Provides a heartbeat endpoint that returns site status, directives,
 * schema version, last completed batch info, and server time.
 * Must be called before every POST /batches/start.
 * </p>
 * <p>
 * Authentication: Custom JWT (HMAC-SHA256) via JwtAuthenticationFilter.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US2 - Heartbeat Pre-Batch Sync Check
 */
@RestController
@RequestMapping("/api/v1/device/heartbeat")
public class HeartbeatController {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatController.class);

    private final HeartbeatService heartbeatService;
    private final AuthorizationHelper authorizationHelper;

    public HeartbeatController(HeartbeatService heartbeatService, AuthorizationHelper authorizationHelper) {
        this.heartbeatService = heartbeatService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Record heartbeat and return site status with directives.
     * <p>
     * GET /api/v1/device/heartbeat
     * </p>
     *
     * @return heartbeat response with directives, schema, and last batch info
     */
    @GetMapping
    public ResponseEntity<HeartbeatResponseDto> heartbeat() {
        UUID siteId = authorizationHelper.getAuthenticatedSiteId();

        logger.debug("Heartbeat request: siteId={}", siteId);

        HeartbeatResponseDto response = heartbeatService.recordHeartbeat(siteId);
        return ResponseEntity.ok(response);
    }
}
