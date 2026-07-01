-- V32: Delta Client v2 — per-table insert/update/delete stats on changelog segments
-- (feature 022, batch history addition). Surfaces "what changed" for a Delta batch in Upload
-- History instead of the always-zero uploaded_files_count (Delta writes no uploaded_files).
-- Nullable: pre-existing segment rows have no stats and simply show no per-table breakdown.

ALTER TABLE changelog_segments ADD COLUMN stats JSONB;
