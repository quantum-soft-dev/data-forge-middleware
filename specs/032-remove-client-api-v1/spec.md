# Feature 032 — Remove client API v1

## Goal

Retire the unreachable custom-credential client surface under `/api/dfc/**` and make
Delta Client v2 the only ingestion mode.

## Decisions

- Existing `client_api_version = 'V1'` rows are migrated to `V2`; sites remain active.
- The `client_api_version` column and response field remain for compatibility, but the
  only valid value is `V2`.
- HTTP file-ingestion writes are not reopened after removing the per-site version guard.
  The stale `/api/v1/device/batches/start` and
  `/api/v1/device/files/batches/{batchId}/upload` mappings are removed. Device auth,
  drain/read operations, and error reporting remain available.
- Integration tests mint ordinary site/account JWTs directly through the test helper.
  Production credential-based token issuance is deleted.
- V45 is used because V44 landed on `develop` after issue #64 was written.

## User stories

### US1 — Removed legacy surface

As an operator, I want requests under `/api/dfc/**` to have no controller or dedicated
security chain so the retired API cannot be used accidentally.

Acceptance criteria:

1. `BatchController`, `FileUploadController`, `SchemaUploadController`, and
   `ErrorLogController` are absent.
2. No production or test security chain matches `/api/dfc/**`.
3. The JWT filter processes only `/api/v1/device/**`.

### US2 — Delta-only sites

As an operator, I want every persisted site to use Delta v2 without deactivating it.

Acceptance criteria:

1. V45 updates every V1 row to V2.
2. The database rejects V1 and any other value.
3. Java and TypeScript expose no V1 enum/type value or V1-only presentation branch.
4. Business services no longer branch on `Site.isDeltaV2()`.

### US3 — V2-only token issuance

As a security maintainer, I want access tokens minted only from an already-authorized
site id.

Acceptance criteria:

1. `TokenService.generateToken(domain, clientSecret)` is absent.
2. Domain-bearing JWT generation/extraction helpers are absent.
3. The integration harness mints test tokens without exercising production legacy
   credentials.

### US4 — Current documentation

As a client developer, I want documentation to describe only Device Flow and Delta gRPC.

Acceptance criteria:

1. `docs/api-unification.md` marks the migration complete and does not advertise
   `/api/dfc/**`.
2. A change request records the data migration, removed paths, and deployment checks.

## Non-goals

- Dropping historical upload/file tables or legacy batch data.
- Removing the Device Flow auth, refresh, drain/read, or error-reporting endpoints.
- Removing the `client_api_version` response field in this change.

