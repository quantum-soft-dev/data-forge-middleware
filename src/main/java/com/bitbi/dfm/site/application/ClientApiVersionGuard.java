package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Per-site strangler rule for the legacy HTTP file API (Delta Client v2 — 022, Task 7).
 *
 * <p>Sites flagged {@link com.bitbi.dfm.site.domain.ClientApiVersion#V2} ingest exclusively via
 * the Delta gRPC API; the HTTP file-path write endpoints (batch start, CSV upload, schema upload
 * on {@code /api/dfc/**} and {@code /api/v1/device/**}) must reject them with
 * {@code 409 Conflict} and machine-readable code {@link #ERROR_CODE}. V1 sites are unaffected.</p>
 *
 * <p>Unknown site ids pass through — authentication owns that failure mode. Drain endpoints
 * (batch complete/fail/cancel/get, file download) and client error logging stay open for all
 * sites and must not call this guard.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ClientApiVersionGuard {

    /**
     * Machine-readable error code returned in the {@code code} field of 409 responses.
     */
    public static final String ERROR_CODE = "CLIENT_API_V2_REQUIRED";

    private final SiteRepository siteRepository;

    public ClientApiVersionGuard(SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    /**
     * Assert that the site may use the HTTP file API.
     *
     * @param siteId site identifier (from JWT claims or the batch being written to)
     * @throws HttpFileApiDisabledException if the site is flagged V2 (Delta gRPC ingestion)
     */
    public void assertHttpFileApiAllowed(UUID siteId) {
        siteRepository.findById(siteId)
                .filter(Site::isDeltaV2)
                .ifPresent(site -> {
                    throw new HttpFileApiDisabledException(siteId);
                });
    }

    /**
     * Thrown when a V2 (Delta gRPC) site calls an HTTP file-path write endpoint.
     * Controllers map it to {@code 409 Conflict} with {@code code = CLIENT_API_V2_REQUIRED}.
     */
    public static class HttpFileApiDisabledException extends RuntimeException {

        public HttpFileApiDisabledException(UUID siteId) {
            super("Site " + siteId + " uses Delta Client v2 (gRPC ingestion); "
                    + "the HTTP file API is disabled for this site");
        }
    }
}
