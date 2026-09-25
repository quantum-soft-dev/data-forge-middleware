-- V59: one replica builds a site's checkpoint at a time (issue #345)
--
-- The nightly CheckpointScheduler fires on every replica at the same second, and nothing kept two
-- pods apart: its ReentrantLock and the fold budget (#178) are per JVM. On the test cluster three
-- pods built the same first checkpoint (a 275 MB frame uploaded three times, two of them ending on
-- uk_checkpoint_site_table), and two incremental builds of one site both ran to completion on two
-- following nights, each pruning behind the other.
--
-- These two columns are a per-site claim with a lease, the shape batch_parquet_artifacts already
-- uses (#036/#040): a replica takes the site with a conditional single-statement write in its own
-- short transaction, renews the lease while its build is alive, and releases it in a finally. A
-- replica that finds the site claimed skips it. A pod that dies mid-build loses the claim when the
-- lease lapses, and the next claimant takes it over. The token is the owner: only the token that
-- holds the claim can renew or release it.
--
-- NULL/NULL means free, which is what every existing row is. Neither column is mapped on the
-- SiteSyncState entity, on purpose: every other writer of this row saves the whole entity, and a
-- save built from a snapshot read before the claim would put a stale value back (#245).

ALTER TABLE site_sync_state
    ADD COLUMN checkpoint_claim_token      UUID,
    ADD COLUMN checkpoint_claim_expires_at TIMESTAMP;

COMMENT ON COLUMN site_sync_state.checkpoint_claim_token IS
    'Which replica currently builds (or prunes, or force-rebuilds) this site''s checkpoint '
    '(issue #345). A random token per visit; NULL when the site is free. Only the holder''s token '
    'can renew or release it.';
COMMENT ON COLUMN site_sync_state.checkpoint_claim_expires_at IS
    'When the claim lapses unless renewed, UTC wall clock from the database''s own clock. The '
    'holder renews it every third of delta.checkpoint.claim-lease-seconds while its build is '
    'alive; past it, the next claimant takes the site over.';
