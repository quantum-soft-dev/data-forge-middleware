package com.bitbi.dfm.delta.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many times the nightly pass retries a checkpoint table that produced no snapshot (issue #149).
 *
 * <p>Three collaborators have to agree on the number — {@link CheckpointService} skips a row that
 * has spent it, {@link CheckpointScheduler} stops naming the row's site, and
 * {@link CheckpointGivenUpMetrics} gauges the population beyond it — so it lives in one bean rather
 * than in three {@code @Value} annotations that could drift apart.</p>
 *
 * <p><b>The unit is attempts, not time, and that is deliberate.</b> The retry runs from a cron that
 * fires once a night, so a delay-based backoff would only ever express itself as a number of
 * skipped nights; the counter says the same thing without a clock, and survives a restart without
 * one either. The bound governs the <em>dedicated</em> retry only: a site with new segments is
 * visited for its segments and the incremental build there writes every table in its fold whatever
 * this counter says, because that work is happening regardless.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointRetryProperties {

    /**
     * Default attempt ceiling. Five nights is long enough to outlive the transient causes worth
     * retrying for (an S3 outage, a schema the client is late to submit, a scratch volume filled by
     * a neighbour) and short enough that a deterministic failure — an unrenderable value, a table
     * whose schema will never arrive — stops costing a frame download and a whole-site fold inside
     * a week.
     */
    public static final String DEFAULT_MAX_MATERIALIZE_ATTEMPTS = "5";

    private final int maxMaterializeAttempts;

    public CheckpointRetryProperties(
            @Value("${delta.checkpoint.max-materialize-attempts:"
                    + DEFAULT_MAX_MATERIALIZE_ATTEMPTS + "}") int maxMaterializeAttempts) {
        if (maxMaterializeAttempts <= 0) {
            throw new IllegalArgumentException(
                    "delta.checkpoint.max-materialize-attempts must be positive, got "
                            + maxMaterializeAttempts);
        }
        this.maxMaterializeAttempts = maxMaterializeAttempts;
    }

    /**
     * @return failed attempts a checkpoint row may spend before the nightly pass stops retrying it
     */
    public int maxMaterializeAttempts() {
        return maxMaterializeAttempts;
    }
}
