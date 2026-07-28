# Unified API

**Status:** current after feature 032
**Updated:** 2026-07-28

## Client ingestion

Delta Client v2 is the only supported ingestion path:

- OAuth 2.0 Device Authorization Flow and refresh tokens are served under
  `/api/v1/device/auth/**`.
- Session ingestion and schema submission use the Delta v2 gRPC service on port
  `9090`.
- The authenticated HTTP routes under `/api/v1/device/**` are limited to
  draining or reading existing batches, file metadata, and client error
  reporting.

The credential-based `/api/dfc/**` surface has been removed. It has no controller,
security filter chain, or token-issuance method. The transitional HTTP write
routes `/api/v1/device/batches/start` and
`/api/v1/device/files/batches/{batchId}/upload` have also been removed so that
new ingestion cannot bypass Delta v2.

## Current HTTP surfaces

### Device API — custom access token

```text
/api/v1/device/auth/**
/api/v1/device/batches/{batchId}
/api/v1/device/batches/{batchId}/complete
/api/v1/device/batches/{batchId}/complete-with-warnings
/api/v1/device/batches/{batchId}/fail
/api/v1/device/batches/{batchId}/cancel
/api/v1/device/files/batches/{batchId}/files/{fileId}
/api/v1/device/errors/**
```

### User and admin API — Auth0 OAuth2

```text
/api/v1/account/**
/api/v1/accounts/**
/api/v1/sites/**
/api/v1/batches/**
/api/v1/history/**
/api/v1/errors/**
/api/v1/comparisons/**
```

### Plugin APIs

Plugin endpoints keep their dedicated API-key or Basic Auth filter chains:

```text
/api/v1/plugins/bit-bi/**
/api/v1/plugins/parquet-export/**
/download/{token}
```

## Security routing

Security filter chains are selected by path:

1. `/api/v1/device/**` — Data Forge access token.
2. Plugin and one-time download paths — plugin-specific authentication.
3. `/api/v1/**` — Auth0 OAuth2.
4. Any unmatched request is denied.

There is deliberately no matcher for `/api/dfc/**`.

## V1 data migration

Flyway migration `V45__retire_client_api_v1.sql` converts existing
`sites.client_api_version = 'V1'` rows to `V2`, keeps those sites active, and
replaces the old two-value check constraint with `client_api_version = 'V2'`.
Java and TypeScript now expose V2 as the sole client API version.

Operational details and the rollout decision are recorded in
[`cr-remove-client-api-v1.md`](cr-remove-client-api-v1.md).
