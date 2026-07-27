-- Migration: V43__refresh_token_rotation_family.sql
-- Description: Add rotation family identity to refresh tokens
-- Author: Data Forge Team
-- Date: 2026-07-27
--
-- Refresh token reuse detection must revoke the compromised ROTATION CHAIN, not every
-- token of the site. A site legitimately holds several independent chains at once - each
-- run of the Device Authorization Flow starts one - so revoking by site_id let anyone
-- holding a single stale, already-revoked token repeatedly kill the site's live session.
--
-- Backfill strategy: every pre-existing row gets its OWN unique family_id, i.e. each legacy
-- token is a family of one. gen_random_uuid() is VOLATILE, so ADD COLUMN ... DEFAULT rewrites
-- the table and evaluates the default per row rather than storing one shared value.
-- This is deliberately conservative: the parent/child links of historical rotations were never
-- recorded, so guessing them would either fuse unrelated chains (re-opening the same
-- denial-of-service) or invent links that never existed. A legacy token can therefore only
-- ever revoke itself.
--
-- The column is NOT NULL with a database-side default, so family_id can never be NULL:
--   * existing rows are filled by the rewrite above;
--   * an old application instance still running during a rolling deploy inserts without the
--     column and the default assigns a fresh, isolated family - degrading to "family of one"
--     instead of failing the insert or widening a revocation.

ALTER TABLE refresh_tokens
    ADD COLUMN family_id UUID NOT NULL DEFAULT gen_random_uuid();

-- Reuse detection revokes by family; the sweeper and rotation both look tokens up by hash.
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
