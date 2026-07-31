package com.bitbi.dfm.delta.domain.events;

import java.util.UUID;

/**
 * Published when a site's checkpoint pointer advances to a newly materialized checkpoint
 * (035 — issue #89).
 *
 * <p>Exists so the Bit BI plugin can re-capture its delta baselines automatically after a site
 * history wipe without the delta package having to know that plugins exist: the ingestion-side
 * dependency runs strictly plugin → delta, and inverting it for one hook would put a plugin
 * failure on the checkpoint path.</p>
 *
 * <p>Fired on <em>every</em> checkpoint build, scheduled or forced. Listeners must decide for
 * themselves whether this particular one concerns them.</p>
 *
 * @param siteId site whose checkpoint was recorded
 * @param seq    sequence the checkpoint represents
 */
public record CheckpointRecordedEvent(UUID siteId, long seq) {
}
