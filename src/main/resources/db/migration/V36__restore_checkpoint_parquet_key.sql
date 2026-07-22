-- V36: Delta Sync UI (feature 023, B3) — reinstate full Parquet checkpoint materialization.
-- Reverts V34: Parquet is the target egress format (per-segment deltas + full per-table loads
-- from checkpoints); CSV remains the legacy Bit BI path. See design_handoff_delta_sync D1.
ALTER TABLE checkpoints ADD COLUMN s3_key_parquet VARCHAR(1000);
