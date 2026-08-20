package com.bitbi.dfm.delta.domain;

import java.time.LocalDateTime;

/**
 * How long a failing changelog segment is held out of its work queue, and when it stops being an
 * ordinary failure and becomes a standing problem (issue #243).
 *
 * <p>Both segment queues — the Bit BI delta-SQL queue and the delta-Parquet egress — claim the
 * <em>globally</em> oldest per-site head with {@code LIMIT 1}, so before this a segment whose work
 * deterministically threw was offered first on every wake and no other site's work was ever
 * reached. Deferring the failing segment leaves that site's order intact (its own later segments
 * still queue behind it — a per-site seq contract neither queue may break) while every other site
 * drains normally.</p>
 *
 * <p>The delay doubles per attempt and is capped at 64x, the shape
 * {@code delta.batch-parquet.retry-delay-seconds} already uses: a transient outage gets a window
 * wide enough to ride out, and a permanently poisoned segment settles into one cheap retry per cap
 * interval instead of a hot loop. Unlike the batch-parquet twin there is <b>no attempt ceiling that
 * gives up</b>: a segment is the durable queue entry, nothing can re-drive it once its marker is
 * stamped, and its usual causes (a declared schema that does not fit the data, an unreadable
 * object, a misconfigured ceiling) are operator-repairable — so the attempt count escalates
 * reporting rather than discarding work. The outer horizon stays batch retention (#212).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class QueueRetryBackoff {

    /** Doubling cap, in powers of two: the 7th attempt onwards waits {@code 64 x} the base delay. */
    private static final int MAX_DOUBLINGS = 6;

    private final int retryDelaySeconds;
    private final int poisonAfterAttempts;

    /**
     * @param retryDelayKey        configuration key of {@code retryDelaySeconds}, quoted when it is
     *                             out of range (#185: a refusal names the key and the value)
     * @param retryDelaySeconds    base delay before a failed segment is offered again; {@code >= 1}
     * @param poisonAttemptsKey    configuration key of {@code poisonAfterAttempts}
     * @param poisonAfterAttempts  consecutive failed attempts after which the segment is reported as
     *                             poisoned rather than merely retried; {@code >= 1}
     */
    public QueueRetryBackoff(String retryDelayKey, int retryDelaySeconds,
                             String poisonAttemptsKey, int poisonAfterAttempts) {
        if (retryDelaySeconds < 1) {
            throw new IllegalArgumentException(
                    retryDelayKey + " must be at least 1 second, got " + retryDelaySeconds);
        }
        if (poisonAfterAttempts < 1) {
            throw new IllegalArgumentException(
                    poisonAttemptsKey + " must be at least 1, got " + poisonAfterAttempts);
        }
        this.retryDelaySeconds = retryDelaySeconds;
        this.poisonAfterAttempts = poisonAfterAttempts;
    }

    /**
     * When a segment that has now failed {@code attempts} times may be claimed again.
     *
     * @param now      the failure instant (UTC, as the row's timestamps are)
     * @param attempts the segment's failed-attempt count including this failure; a value below one
     *                 is read as the first attempt
     * @return the instant the segment leaves its cooldown
     */
    public LocalDateTime nextRetryAt(LocalDateTime now, int attempts) {
        int doublings = Math.min(Math.max(attempts, 1) - 1, MAX_DOUBLINGS);
        return now.plusSeconds((long) retryDelaySeconds << doublings);
    }

    /**
     * Whether a segment at this attempt count is a standing problem rather than a passing failure.
     *
     * @param attempts the segment's failed-attempt count
     * @return {@code true} once the configured threshold is reached
     */
    public boolean isPoisoned(int attempts) {
        return attempts >= poisonAfterAttempts;
    }
}
