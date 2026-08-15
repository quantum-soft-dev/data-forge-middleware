package com.bitbi.dfm.delta.domain.events;

import com.bitbi.dfm.delta.domain.SiteEpoch;

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
 * <p>The event is published one statement after the build's guarded pointer write commits, so a
 * wipe or a re-baseline can commit in that gap and the event then describes a history that no longer
 * exists. {@code epoch} is what makes that detectable: a listener re-checks it against the site's
 * current epoch before acting on the event (issue #142). Publishing inside the guard's
 * transaction instead is not an option — {@code DeltaWipeReinitListener} is a synchronous listener
 * in its own {@code REQUIRES_NEW} transaction and would block on the {@code site_sync_state} row
 * lock the suspended guard transaction still holds.</p>
 *
 * @param siteId site whose checkpoint was recorded
 * @param seq    sequence the checkpoint represents
 * @param epoch  the epoch pair the build folded at
 */
public record CheckpointRecordedEvent(UUID siteId, long seq, SiteEpoch epoch) {
}
