-- V34: Delta Client v2 — retire the floor Parquet snapshot (022, Task 8).
-- Parquet egress is now per-segment and event-driven (egress/{site}/{table}/delta/…, V33 queue);
-- checkpoints keep only the legacy CSV (Bit BI) and the frame seed. The floor snapshot.parquet
-- and the _change_date change-feed partitions are no longer written.

ALTER TABLE checkpoints DROP COLUMN IF EXISTS s3_key_parquet;
