# Local Auth0 Setup (Backend + Frontend)

This document explains where each OAuth/Auth0 value comes from, and provides a checklist to run the project locally
without AWS (using LocalStack for S3 and Docker Compose for infra).

## What Comes From Where

Backend reads Auth0 settings via environment variables mapped in:
- `/Users/boris/projects/bit-bi/data-forge-middleware/src/main/resources/application.yml`

Frontend reads Auth0 settings via Vite env vars:
- `/Users/boris/projects/bit-bi/data-forge-middleware/frontend/.env.local`

Auth0 Dashboard provides the actual values (domain, API identifier, client IDs, secrets).

## Required Values

### Backend (.env)

These values must exist for the backend to start in local/dev mode.

- `AUTH0_DOMAIN`
  - Source: Auth0 Dashboard -> Tenant domain
  - Example: `dev-dfm.us.auth0.com`

- `AUTH0_ISSUER`
  - Source: derived from `AUTH0_DOMAIN`
  - Rule: `https://<AUTH0_DOMAIN>/`
  - Example: `https://dev-dfm.us.auth0.com/`
  - Note: Spring also uses `https://${auth0.domain}/` for resource server issuer; these should match.

- `AUTH0_AUDIENCE`
  - Source: Auth0 Dashboard -> Applications -> APIs -> (Data Forge API) -> `Identifier`
  - Example: `https://dev.dfm.bitbi.io`

- `AUTH0_CLAIMS_NAMESPACE`
  - Source: Auth0 Dashboard -> Actions -> (Add Roles to Access Token) -> `const namespace = '...'`
  - Recommendation: set equal to `AUTH0_AUDIENCE` to keep it simple.
  - Example: `https://dev.dfm.bitbi.io`

- `AUTH0_MGMT_CLIENT_ID`
- `AUTH0_MGMT_CLIENT_SECRET`
  - Source: Auth0 Dashboard -> Applications -> Applications -> (Data Forge Management Client, Machine to Machine)
  - Note: keep secret only in `.env` (gitignored). Do not commit.

- `AUTH0_MGMT_AUDIENCE`
  - Source: derived from `AUTH0_DOMAIN`
  - Rule: `https://<AUTH0_DOMAIN>/api/v2/`
  - Example: `https://dev-dfm.us.auth0.com/api/v2/`

Optional but typical:
- `AUTH0_TOKEN_EXPIRY_BUFFER_SECONDS` (default is fine)

### Frontend (frontend/.env.local)

- `VITE_AUTH0_DOMAIN`
  - Source: same as `AUTH0_DOMAIN`

- `VITE_AUTH0_CLIENT_ID`
  - Source: Auth0 Dashboard -> Applications -> Applications -> (Data Forge Admin UI, SPA) -> `Client ID`

- `VITE_AUTH0_AUDIENCE`
  - Source: same as `AUTH0_AUDIENCE` (API Identifier)

- `VITE_AUTH0_CLAIMS_NAMESPACE`
  - Source: same as `AUTH0_CLAIMS_NAMESPACE`

- `VITE_API_BASE_URL`
  - For local: `http://localhost:8080`

## Auth0 Dashboard Checklist

### 1) API (Audience)

Auth0 Dashboard:
- Applications -> APIs -> (Data Forge API)
- Confirm `Identifier` equals the value you set as `AUTH0_AUDIENCE` / `VITE_AUTH0_AUDIENCE`.

### 2) SPA Application (Frontend)

Auth0 Dashboard:
- Applications -> Applications -> Data Forge Admin UI (Single Page Application)

Settings to verify:
- Allowed Callback URLs includes: `http://localhost:3000`
- Allowed Logout URLs includes: `http://localhost:3000`
- Allowed Web Origins includes: `http://localhost:3000`

Copy:
- `Domain` -> `VITE_AUTH0_DOMAIN`
- `Client ID` -> `VITE_AUTH0_CLIENT_ID`

### 3) M2M Application (Backend)

Auth0 Dashboard:
- Applications -> Applications -> Data Forge Management Client (Machine to Machine)

Copy:
- `Client ID` -> `AUTH0_MGMT_CLIENT_ID`
- `Client Secret` -> `AUTH0_MGMT_CLIENT_SECRET`

Also ensure it has access to Auth0 Management API scopes needed by backend account operations:
- `read:users`, `create:users`, `update:users`, `delete:users`
- `read:roles`, `read:role_members`, `create:role_members`, `delete:role_members`
- `read:users_app_metadata`, `update:users_app_metadata`

### 4) Post-Login Actions (Roles/Claims)

Auth0 Dashboard:
- Actions -> Flows -> Login

Verify:
- `Add Roles to Access Token` is enabled
- It sets claims using a namespace matching `AUTH0_CLAIMS_NAMESPACE`
  - roles claim key: `<namespace>/roles`
  - account id claim key: `<namespace>/accountId`

If you keep the `Account Linking` action in the flow, ensure its secrets are set in Auth0:
- `domain`
- `clientId`
- `clientSecret`

If these secrets are missing, logins may fail at Post-Login time.

## Local Run (No AWS)

Infra (Docker):
- Use `docker-compose.dev.yml` (Postgres, Redis, LocalStack, Keycloak)

Backend:
- `./gradlew bootRun`

Frontend:
- `cd frontend && npm install && npm run dev`

## Sanity Checks

- Backend should start without Auth0 token validation errors.
- Frontend login should succeed, and admin endpoints should return 200 for an ADMIN user.

## Troubleshooting

### Flyway Checksum Mismatch (Validate Failed)

Symptom (example):
- `FlywayValidateException: Migration checksum mismatch for migration version X`

Cause:
- Your local Postgres volume contains an older `flyway_schema_history` (created with older migration file contents).
- Flyway refuses to migrate when applied checksums do not match current files.

Fix options:

1. Preferred for local dev: reset the local DB volume (destroys local data).
   - From the repo root (host or devcontainer):
   ```bash
   docker-compose -f docker-compose.dev.yml down -v
   docker-compose -f docker-compose.dev.yml up -d
   ```
   - Then start backend again:
   ```bash
   ./gradlew bootRun
   ```

2. Alternative (keeps data, not recommended unless you understand the impact): run Flyway repair.
   - This updates checksums in `flyway_schema_history` to match current files.
   ```bash
   ./gradlew flywayRepair
   ./gradlew bootRun
   ```

## Optional: Pull Values From AWS (Secrets Manager)

If the project is deployed in AWS and config is stored in Secrets Manager, you can pull most of the required values
from there instead of copying from the Auth0 UI.

Common locations in this AWS account:
- `dfm-dev/auth0/credentials` (Auth0 domain, audience, management client id/secret)
- `dfm-dev/jwt/secret` (device API JWT secret)
- `dfm-dev/redis/password` (Redis password)
- `dfm-dev/db/app-credentials` (DB app username/password)

Example commands:
```bash
aws sts get-caller-identity
aws secretsmanager get-secret-value --secret-id dfm-dev/auth0/credentials --query SecretString --output text
```

Notes:
- Do not paste the secret values into PRs or chat.
- For local development, keep secrets in `.env` / `frontend/.env.local` (both gitignored).
- If you generate helper files like `.env.aws-*` or `frontend/.env.local.aws-*`, they are also gitignored by this repo.
