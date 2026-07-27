-- 029-batch-per-session: batch = one ingestion session.
-- 1. Activity timestamp for Delta v2 streaming batches: the timeout sweeper evaluates
--    COALESCE(last_activity_at, started_at) so legacy v1 batches (always NULL) keep the
--    started_at-based timeout unchanged.
ALTER TABLE batches ADD COLUMN last_activity_at TIMESTAMP;

COMMENT ON COLUMN batches.last_activity_at IS
    'Last ingestion activity of a Delta v2 streaming session (NULL for v1 batches); batch timeout uses COALESCE(last_activity_at, started_at)';

-- 2. Segments are now many-to-one to batches (multiple ~100-record seals per session);
--    history detail and list aggregation query segments by batch_id.
CREATE INDEX idx_changelog_segments_batch_id ON changelog_segments(batch_id);
