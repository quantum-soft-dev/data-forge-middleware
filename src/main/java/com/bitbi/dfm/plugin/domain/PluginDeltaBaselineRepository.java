package com.bitbi.dfm.plugin.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repository for {@link PluginDeltaBaseline} persistence (026-bitbi-delta-sql).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface PluginDeltaBaselineRepository {

    PluginDeltaBaseline save(PluginDeltaBaseline baseline);

    List<PluginDeltaBaseline> findBySiteId(UUID siteId);

    List<PluginDeltaBaseline> findByAccountPluginIdAndSiteId(Long accountPluginId, UUID siteId);

    int deleteByAccountPluginIdAndSiteId(Long accountPluginId, UUID siteId);

    /**
     * Delete every baseline of a site across all activations (issue #89 — history wipe). The site
     * row survives a wipe, so no cascade fires and the delete must be explicit.
     *
     * @param siteId site identifier
     * @return number of baselines deleted
     */
    int deleteBySiteId(UUID siteId);

    /**
     * The site's per-table baseline seqs, keyed by table name (input to the SQL generation
     * filter: emit iff {@code seq > baselineSeq(table)}, missing table → 0).
     */
    default Map<String, Long> baselineSeqsBySiteId(UUID siteId) {
        return findBySiteId(siteId).stream()
                .collect(Collectors.toMap(PluginDeltaBaseline::getTableName, PluginDeltaBaseline::getBaselineSeq));
    }
}
