# Implementation plan

## T01 — Specify the retirement

Record the migration choice, compatibility boundary, rollout checks, and ordered tasks.

## T02 — Make sites Delta-only

Write tests for the single-value invariant, add V45, collapse Java/TypeScript V1
branches, and keep HTTP file writes closed while removing `ClientApiVersionGuard`.

## T03 — Remove `/api/dfc/**`

Add a negative contract for representative legacy routes, remove the four controllers
and both production/test legacy security chains, and narrow the JWT filter.

## T04 — Remove legacy token minting

Update token/provider tests and the integration token helper first, then remove
credential-based and domain-bearing token APIs.

## T05 — Documentation and verification

Update current guides, run backend and frontend per-task gates, then run the
Testcontainers integration suite required before PR.

## Rollout

Before deployment:

```sql
SELECT client_api_version, count(*)
FROM sites
WHERE is_active
GROUP BY 1;
```

V45 preserves every site and converts V1 to V2. A temporary database trigger also
normalizes `V1` writes from old pods during the rolling window before the V2-only
constraint runs. After migration:

```sql
SELECT count(*) FROM sites WHERE client_api_version <> 'V2';
```

must return zero.

Clients must complete Device Flow authorization and use Delta gRPC before deployment.
The repository and owner audit attached to issue #64 establishes that there are no
remaining V1 consumers.

After all pre-V45 pods are drained, remove the compatibility trigger and function in
V46. Until then, rollback of the application alone is safe for site creation because
legacy V1 writes are stored as V2; a full rollback still requires the database snapshot
described in the change request.
