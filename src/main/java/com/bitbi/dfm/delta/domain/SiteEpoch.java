package com.bitbi.dfm.delta.domain;

/**
 * The pair of epochs that together say "the site's server-side history is still the one you read"
 * (issues #136, #142).
 *
 * <p>{@link #generation} is the wire epoch (035): a history wipe bumps it and nothing else does,
 * because it travels to the Delta v2 client and tells it to drop its journal and reset its seq
 * counter. {@link #baselineEpoch} is server-internal and is bumped by a wipe <em>and</em> by an
 * ordinary FULL_SNAPSHOT re-baseline, which discards the site's checkpoints just as thoroughly but
 * must never say anything to the client.</p>
 *
 * <p>They are carried and compared as a pair rather than as one "strongest" counter, and the reason
 * is the rolling deployment. Neither field subsumes the other across versions: a pod that predates
 * {@code baseline_epoch} bumps {@code generation} alone, so during the mixed-version window a wipe
 * issued from an old pod is invisible to a new pod that watches only the baseline epoch — the exact
 * hole #136 closed, re-opened by the fix for #142. Comparing both means a mover of either kind, from
 * either version, refuses the write.</p>
 *
 * @param generation    the wire epoch, {@code site_sync_state.generation}
 * @param baselineEpoch the server-internal baseline epoch, {@code site_sync_state.baseline_epoch}
 */
public record SiteEpoch(long generation, long baselineEpoch) {

    /** What a site with no {@code site_sync_state} row is on: it has never synced. */
    public static final SiteEpoch INITIAL = new SiteEpoch(0L, 0L);

    /**
     * Read both epochs off a sync-state row.
     *
     * @param state the site's sync state
     * @return the epoch pair the row is currently on
     */
    public static SiteEpoch of(SiteSyncState state) {
        return new SiteEpoch(state.getGeneration(), state.getBaselineEpoch());
    }

    @Override
    public String toString() {
        return "generation " + generation + " / baseline epoch " + baselineEpoch;
    }
}
