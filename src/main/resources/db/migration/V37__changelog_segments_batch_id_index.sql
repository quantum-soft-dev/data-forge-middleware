-- V37: index changelog_segments.batch_id (feature 022 hardening, review r4)
--
-- The hot queries findByBatchId / findByBatchIdIn (every Upload History page load and batch-detail
-- view) and deleteByBatchId (batch retention + admin delete, which also drives the FK check) all
-- filtered on batch_id with no supporting index — a sequential scan over a table that grows by one
-- row per delta session per site. This index turns those into index lookups.

CREATE INDEX idx_segment_batch_id ON changelog_segments(batch_id);
