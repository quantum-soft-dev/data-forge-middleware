-- 029-batch-per-session: batch = one ingestion session.
-- (V39 is reserved by the in-flight 028-parquet-export-plugin branch.)
--
-- Activity timestamp for Delta v2 streaming batches: the timeout sweeper evaluates
-- COALESCE(last_activity_at, started_at) so legacy v1 batches (always NULL) keep the
-- started_at-based timeout unchanged. Touched by the ingest path at a bounded cadence
-- (session start/resume, Ack watermark, segment seal).
--
-- changelog_segments.batch_id is now genuinely many-to-one (multiple ~100-record seals per
-- session); its supporting index already exists (V37: idx_segment_batch_id).
ALTER TABLE batches ADD COLUMN last_activity_at TIMESTAMP;

COMMENT ON COLUMN batches.last_activity_at IS
    'Last ingestion activity of a Delta v2 streaming session (NULL for v1 batches); batch timeout uses COALESCE(last_activity_at, started_at)';
