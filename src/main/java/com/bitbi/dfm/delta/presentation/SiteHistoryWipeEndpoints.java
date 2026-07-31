package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeRequestDto;
import com.bitbi.dfm.delta.presentation.dto.SiteHistoryWipeResponseDto;
import com.bitbi.dfm.site.domain.Site;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * The site-history-wipe endpoint body, shared by the owner and admin controllers (035 — issue #89).
 *
 * <p>The two surfaces differ only in how they authorize the caller; everything after that — the
 * confirmation gate, the outcome mapping and the audit initiator — must not drift between them.</p>
 */
final class SiteHistoryWipeEndpoints {

    private static final Logger logger = LoggerFactory.getLogger(SiteHistoryWipeEndpoints.class);

    private SiteHistoryWipeEndpoints() {
    }

    /**
     * Run a wipe for an already-authorized site.
     *
     * @param wipeService the wipe service
     * @param site        the site whose history to destroy
     * @param request     the confirmation body
     * @param initiator   who asked, for the audit trail
     * @return 200 with the summary, or 409 with a retry-or-stop-the-client status
     * @throws IllegalArgumentException when the confirmation does not match the site's name (400)
     */
    static ResponseEntity<Object> wipe(DeltaSiteWipeService wipeService,
                                       Site site,
                                       SiteHistoryWipeRequestDto request,
                                       DeltaSiteWipeService.Initiator initiator) {
        // Matched against siteName, not domain. The issue's example used a hostname-shaped domain,
        // but `sites.domain` is the legacy composite `{accountId}_{siteName}` that no API exposes
        // and no operator could type — siteName is the identity the UI shows and the client
        // authenticates with.
        //
        // Checked here rather than with bean validation: a blank body, a missing field and a typo
        // are the same mistake, and all three must be the same 400 rather than a 500 on the
        // un-deserializable case.
        String confirm = request == null ? null : request.confirm();
        if (confirm == null || !confirm.equals(site.getSiteName())) {
            throw new IllegalArgumentException(
                    "Confirmation must be the site's name (" + site.getSiteName() + ")");
        }

        try {
            return ResponseEntity.ok(SiteHistoryWipeResponseDto.fromSummary(
                    wipeService.wipe(site, initiator)));
        } catch (DeltaSiteWipeService.SessionInProgressException e) {
            logger.info("Site history wipe refused, session live: siteId={}", site.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "session-in-progress"));
        } catch (DeltaSiteWipeService.ConcurrentSessionException e) {
            logger.warn("Site history wipe rolled back, batch committed concurrently: siteId={}",
                    site.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "concurrent-session"));
        }
    }
}
