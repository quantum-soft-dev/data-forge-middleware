# Local dev / test environment — Delta Client v2 (022)

Run the backend locally and exercise the **Delta v2 gRPC ingestion** server (port **9090**)
without a real Auth0 tenant. HTTP API stays on 8080.

## Prerequisites

- Docker (for postgres + localstack + redis)
- JDK 25 (Gradle toolchain picks it up automatically)
- `grpcurl` and `python3` (for the smoke test): `brew install grpcurl`

## Required code tweaks (working tree, not committed)

The Delta gRPC path does **not** use Auth0, but two beans resolved Auth0 **eagerly at startup**
and crashed the context when the tenant was unreachable. Both are now deferred so the app boots
Auth0-less; behaviour is unchanged when Auth0 is configured:

- `auth/config/Auth0Configuration#managementAPI` → `@ConditionalOnExpression("'${auth0.management.client-id:}' != ''")`
  (the bean is unused — `Auth0ManagementApiClient` builds its own client — and matches its own Javadoc).
- `auth/config/Auth0SecurityConfig#auth0JwtDecoder` → wrapped in `SupplierJwtDecoder` (lazy issuer/JWKS
  resolution on first admin/user request, like Spring Boot's default for `issuer-uri`).

## 1. Start dependencies

```bash
docker compose up -d postgres localstack redis
```

localstack auto-creates the `dfm-uploads` bucket (see `docker/localstack/init-s3.sh`).

## 2. Run the backend (dev profile)

```bash
SPRING_PROFILES_ACTIVE=dev AWS_S3_BUCKET_NAME=dfm-uploads ./gradlew bootRun
```

Why `AWS_S3_BUCKET_NAME`: the `dev` profile defaults the bucket to `data-forge-bucket`, but localstack
creates `dfm-uploads`. Everything else (DB `dfm/dfm_password`, redis password, S3 endpoint
`http://localhost:4566`, the fixed dev `jwt.secret`) already has correct `dev` defaults in
`application-dev.yml`.

Look for: `Delta gRPC server started on port 9090` and `Started DataForgeMiddlewareApplication`.

## 3. Seed a V2 site

Flyway creates the schema on first boot; then seed an account + a `client_api_version=V2` site:

```bash
docker exec -i dfm-postgres psql -U postgres -d dfm < local-dev/seed-delta-site.sql
```

(`application-dev.yml` also runs `classpath:db/seed` — this script is an explicit, fixed-ID addition.)

## 4. Smoke test (mint token + GetSyncState)

```bash
./local-dev/smoke-test.sh
```

Expected (fresh site, no sync state): `action: PROCEED`, all seqs `0`.

The token is a custom **HS256 JWT** (claims `siteId`/`accountId`) signed with the dev `jwt.secret` —
the same one Client API v1 issues; the Delta interceptor reuses `TokenService.validateToken`. No Auth0.

```bash
# raw token for ad-hoc grpcurl calls:
TOKEN="$(./local-dev/mint-token.py)"
```

## Calling the other RPCs

`grpcurl` needs the `.proto` (server reflection is not enabled):
`-import-path src/main/proto -proto delta-ingestion.proto`. Service: `com.bitbi.dfm.delta.v2.DeltaIngestion`.

**SubmitSchema** (unary) — required before the first session for a CDC table:

```bash
grpcurl -plaintext -import-path src/main/proto -proto delta-ingestion.proto \
  -H "authorization: Bearer ${TOKEN}" \
  -d '{"tables":{"orders":{"columns":[
        {"name":"id","type":"integer","nullable":false},
        {"name":"total","type":"numeric(10,2)","nullable":true}],
      "primaryKey":["id"]}}}' \
  localhost:9090 com.bitbi.dfm.delta.v2.DeltaIngestion/SubmitSchema
```

**StreamChanges** is bidirectional streaming (SessionStart → ChangeRecord* → SessionEnd). It is
awkward to drive by hand — use the C#/.NET Delta client (coordinated repo) for full session/ingest
testing. See `docs/delta-client-v2-guide.md` for the lifecycle and `docs/cr-delta-client-v2.md`.

## Using a real Auth0 tenant (optional)

The Delta v2 gRPC path does **not** need Auth0 (HS256 tokens). Configure Auth0 only to exercise the
**HTTP admin/user API** (`/api/admin/**`, `/api/v1/**`) and the Management API (account CRUD).

```bash
cp local-dev/auth0.env.example local-dev/auth0.env   # then fill in your tenant (gitignored)
./local-dev/run-with-auth0.sh
```

**Gotcha:** `AUTH0_ISSUER` and `AUTH0_MGMT_AUDIENCE` are *not* derived from `AUTH0_DOMAIN` in
`application-dev.yml` — they default to the fake `dev-example` tenant. Set them explicitly and
consistently (`AUTH0_ISSUER=https://<domain>/`, `AUTH0_MGMT_AUDIENCE=https://<domain>/api/v2/`).

The two code tweaks above stay compatible: once `AUTH0_MGMT_CLIENT_ID` is set the `managementAPI`
bean is created again, and the lazy decoder resolves the real JWKS on first request — no revert needed.

**Tenant prerequisites:**
- An **API** registered with identifier = `AUTH0_AUDIENCE` (e.g. `https://api.dataforge.com`).
- A **Machine-to-Machine** app authorized for the Auth0 Management API → its client id/secret go in
  `AUTH0_MGMT_CLIENT_ID` / `AUTH0_MGMT_CLIENT_SECRET`.
- A **Post-Login Action** that adds the custom claims `…/roles` and `…/accountId` under
  `AUTH0_CLAIMS_NAMESPACE` (these come from interactive login, not client-credentials).

**Getting an admin/user access token to call the HTTP API:** log in through the frontend SPA and copy
the access token (the Action injects `roles`/`accountId`). A bare client-credentials grant yields a
token **without** those custom claims, so it won't satisfy `ROLE_ADMIN`/`ROLE_USER` checks.

**Full stack (frontend too):** copy `frontend/.env.local.example` → `frontend/.env.local`, set
`VITE_AUTH0_DOMAIN` / `VITE_AUTH0_CLIENT_ID` (the **SPA** app) / `VITE_AUTH0_AUDIENCE` /
`VITE_AUTH0_CLAIMS_NAMESPACE`, then `npm --prefix frontend run dev`.

> Don't put `JWT_SECRET` in `auth0.env` — leaving it unset keeps the dev `jwt.secret`, so the Delta
> HS256 helpers (`mint-token.py`) keep working. If you do override it, pass the same value to the helper.

## Reset

```bash
docker exec -i dfm-postgres psql -U postgres -d dfm \
  -c "DELETE FROM sites WHERE id='0de17a00-0000-4000-8000-0000000000a1';"
# or wipe everything:
docker compose down -v
```
