# CR-032: Remove client API v1

## Summary

The custom-JWT client API under `/api/dfc/**` is retired. Device Authorization Flow
remains the only way for a client to obtain access and refresh tokens, and Delta gRPC is
the only ingestion transport.

Issue #64 records the consumer audit: the web frontend has no legacy network calls, the
Windows client uses only Device Flow plus Delta gRPC, and the owner confirmed there are
no other consumers. V1 token issuance had already been unreachable since November 2025.

## Data decision

Existing V1 sites are migrated to V2 rather than deactivated. Deactivation would remove
otherwise valid sites from service without improving safety; leaving V1 values after
removing the Java enum would create unreadable rows.

Migration V45:

1. updates every `sites.client_api_version = 'V1'` row to `V2`;
2. keeps sites active and preserves their identifiers and history;
3. replaces the check constraint so only `V2` is accepted.

Rolling deployments remain safe while old pods drain: V45 installs a temporary
`BEFORE INSERT OR UPDATE` trigger that rewrites an explicitly supplied `V1` to `V2`
before the constraint is evaluated. Unknown values still fail the constraint. Remove
the compatibility trigger and function in V46 only after every pre-V45 pod has been
drained.

The column stays in place for response compatibility. Its removal, if desired, is a
separate API/schema change.

## Removed runtime surface

- the dedicated `/api/dfc/**` security filter chain;
- `BatchController`, `FileUploadController`, `SchemaUploadController`, and
  `ErrorLogController`;
- credential-based production token issuance and domain-bearing JWT helpers;
- the V1 enum value and V1-only backend/frontend behavior.

Removing the per-site version guard must not reopen file uploads for sites migrated to
V2. Therefore the unused Device REST write mappings for starting a file batch and
uploading multipart files are removed as part of the same cutover. Device auth,
refresh, batch drain/read, file metadata, and error logging remain.

## Operational checks

Run before deployment:

```sql
SELECT client_api_version, count(*)
FROM sites
WHERE is_active
GROUP BY 1;
```

Run after Flyway:

```sql
SELECT count(*) AS non_v2_sites
FROM sites
WHERE client_api_version <> 'V2';
```

The expected result is `0`. No site is deactivated by V45.

During the rollout, verify that no old pods remain before scheduling V46:

```sql
SELECT count(*) AS stored_v1_sites
FROM sites
WHERE client_api_version = 'V1';
```

The trigger makes the expected result `0` even when an old pod creates a site during
the rolling window.

## Historical plugin files

The Bit BI files API prefers reconstructed checkpoint CSVs. Sites migrated from V1 can
have historical `uploaded_files` but no checkpoint yet; for those sites the list and
download endpoints fall back to the latest uploaded file records. The retirement does
not make historical file-backed data unreadable.

## Rollback

Flyway migrations are forward-only. Application rollback to a build that still expects
V1 is not supported after V45; restore the previous application and database snapshot
together if an emergency rollback is required.
