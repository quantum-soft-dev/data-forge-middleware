# Quickstart: Parquet Export Plugin (028)

## Client walkthrough

```bash
# 1. Owner activates the plugin (Auth0 token) — response contains "apiKey": "login:password" ONCE
curl -X POST https://host/api/v1/plugins/parquet-export/activate \
  -H "Authorization: Bearer $AUTH0_TOKEN" -H "Content-Type: application/json" -d '{"pluginData":{}}'
```

```bash
# 2. Client lists Parquet files newer than a timestamp (Basic Auth = the credentials from step 1)
curl -u 'pex_Ab3xY9Qm2Lk4:Kf82jdOq81mZnW4xTr5vBc7yLh0aPe3s' \
  'https://host/api/v1/plugins/parquet-export/files?since=2026-07-27T00:00:00&type=delta'
```

```bash
# 3. Client follows a one-time link (no credentials; -L follows the 302 to S3)
curl -L -o orders_seq100-250.parquet \
  'https://host/api/v1/plugins/parquet-export/download/vN3...Qw'
# Second attempt on the same URL → 410 Gone
```

Incremental sync pattern: remember `max(producedAt)` from each listing and pass it as the next `since`.

## Verification checklist (manual / integration)

1. **Activation**: activate → response has `apiKey` in `login:password` form; `plugin_data` has `login` + `passwordHash` (BCrypt), never the raw password. Re-activate while active → no credentials in response.
2. **Rotation**: `POST /api/v1/account/plugins/parquet-export/rotate-password` → new password works, old one 401s.
3. **Listing**: seed a site with egressed segments (`egress_at` set, `stats` non-null) and a checkpoint with `s3_key_parquet`; verify `since`/`siteId`/`table`/`type` filters; verify another account's sites never appear; verify `download_links` rows registered (count = files returned).
4. **One-time download**: 302 → S3 URL differs per consume and expires ~60 s; repeat GET → 410; unknown token → 404; concurrent GET race (2 threads) → exactly one 302.
5. **Deactivation**: deactivate plugin → `/files` 401; pre-registered unconsumed link → 410.
6. **Rate limit**: >100 listing calls/min for one account → 429 + `Retry-After`.
7. **Purge**: rows consumed/expired older than 7 days deleted by scheduler; fresh rows retained.
8. **Security chain**: `/api/v1/plugins/parquet-export/**` never reachable with an Auth0 token via the admin chain (denyAll carve-out); `SecurityFilterChainTest` green.

## Gates

- Per task: `./gradlew test -PexcludeIntegration`
- Before PR: `./gradlew integrationTest` (Testcontainers: PostgreSQL + LocalStack S3 — covers listing→consume→redirect end-to-end and the concurrency race)
