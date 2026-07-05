package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk sync-health projection for the site list (feature 023 — Delta Sync UI, B10).
 *
 * <p>One call returns the raw health inputs (watermark, checkpoint pointer, updatedAt) for
 * every Delta v2 site of an account — the site-list badge polls this instead of issuing one
 * sync-state request per site. V1 sites are omitted entirely.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSyncHealthService {

    private final SiteRepository siteRepository;
    private final SiteSyncStateRepository syncStateRepository;

    public DeltaSyncHealthService(SiteRepository siteRepository, SiteSyncStateRepository syncStateRepository) {
        this.siteRepository = siteRepository;
        this.syncStateRepository = syncStateRepository;
    }

    /**
     * Health entries for all Delta v2 sites of an account, sorted by site id for a stable order.
     *
     * @param accountId account identifier
     * @return one entry per V2 site; {@code state} is null when the client never connected
     */
    @Transactional(readOnly = true)
    public List<SiteHealth> listHealthForAccount(UUID accountId) {
        List<Site> v2Sites = siteRepository.findByAccountId(accountId).stream()
                .filter(Site::isDeltaV2)
                .toList();
        if (v2Sites.isEmpty()) {
            return List.of();
        }

        Map<UUID, SiteSyncState> states = syncStateRepository
                .findBySiteIdIn(v2Sites.stream().map(Site::getId).toList()).stream()
                .collect(Collectors.toMap(SiteSyncState::getSiteId, Function.identity()));

        return v2Sites.stream()
                .map(site -> new SiteHealth(site.getId(), states.get(site.getId())))
                .sorted(Comparator.comparing(SiteHealth::siteId))
                .toList();
    }

    /**
     * Health inputs of one V2 site.
     *
     * @param siteId site identifier
     * @param state  the site's sync state, or null when the client never connected
     */
    public record SiteHealth(UUID siteId, SiteSyncState state) {
    }
}
